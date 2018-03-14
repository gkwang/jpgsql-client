package io.zrz.jpgsql.client.opj;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.postgresql.PGNotification;
import org.postgresql.core.NativeQuery;
import org.postgresql.core.Oid;
import org.postgresql.core.ParameterList;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.TransactionState;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgConnection;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.zrz.jpgsql.client.CombinedQuery;
import io.zrz.jpgsql.client.CommandStatus;
import io.zrz.jpgsql.client.CopyQuery;
import io.zrz.jpgsql.client.ErrorResult;
import io.zrz.jpgsql.client.NotifyMessage;
import io.zrz.jpgsql.client.Query;
import io.zrz.jpgsql.client.QueryParameters;
import io.zrz.jpgsql.client.QueryResult;
import io.zrz.jpgsql.client.QueryResultKind;
import io.zrz.jpgsql.client.SimpleQuery;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * deal with postgresql API directly, avoid the JDBC bullshit.
 */

@Slf4j
class PgLocalConnection implements PgRawConnection {

  /**
   * automatic begin on 1st statement should not be done.
   */

  public static final int SuppressBegin = QueryExecutor.QUERY_SUPPRESS_BEGIN;

  /**
   * this query is unlikely to be reused.
   */

  public static final int OneShot = QueryExecutor.QUERY_ONESHOT;

  /**
   * not expecting results
   */

  public static final int NoResults = QueryExecutor.QUERY_NO_RESULTS;

  private final PgConnection conn;

  private final QueryExecutor exec;

  private final PgThreadPooledClient pool;

  /**
   *
   * @param pool
   * @param conn
   * @param pool
   */

  PgLocalConnection(final PgThreadPooledClient pool, final PgConnection conn) {
    this.pool = pool;
    this.conn = conn;
    this.exec = this.conn.getQueryExecutor();
    try {
      log.debug("disabling auto-commit");
      conn.setAutoCommit(false);
    }
    catch (final SQLException e) {
      // should this ever fail?
      // either way, we abosrb and handle later.
      log.error("exception setting auto commit mode", e);
    }
  }

  @Override
  public PgConnection getConnection() {
    return this.conn;
  }

  @Override
  public PgThreadPooledClient getClient() {
    return this.pool;
  }

  /**
   * close the connection. don't throw if it fails.
   */

  void close() {
    try {
      if (!this.conn.isClosed()) {
        this.conn.close();
      }
    }
    catch (final Exception ex) {
      // nothing to do about this ...
      log.warn("got exception closing connection", ex);
    }
  }

  /**
   * cache of queries, so that they remain prepared on the JDBC client side.
   */

  private final LoadingCache<Query, org.postgresql.core.Query> cache = CacheBuilder.newBuilder()
      .maximumSize(1000)
      .weakKeys()
      .weakValues()
      .expireAfterWrite(5, TimeUnit.MINUTES)
      .build(
          new CacheLoader<Query, org.postgresql.core.Query>() {
            @Override
            public org.postgresql.core.Query load(final Query query) throws Exception {
              final List<NativeQuery> nqs = query.getSubqueries().stream()
                  .map(q -> PgLocalNativeQuery.create(q.sql(), q.parameterCount()))
                  .collect(Collectors.toList());
              return PgLocalConnection.this.conn.getQueryExecutor().wrap(nqs);
            }
          });

  /**
   * execute the query.
   * 
   * @param suppressbegin2
   * @param stdin
   *
   * @param named
   * @throws SQLException
   */

  void execute(final Query query, final QueryParameters params, final FlowableEmitter<QueryResult> emitter)
      throws SQLException {
    this.execute(query, params, emitter, 0, 0);
  }

  void execute(final Query query, final QueryParameters params, final FlowableEmitter<QueryResult> emitter, int fetchSize, int flags)
      throws SQLException {

    log.debug("executing {}, params {}", query, params);

    if (query instanceof CopyQuery) {
      copy((CopyQuery) query, emitter, flags);
      return;
    }

    final org.postgresql.core.Query pgquery = this.cache.getUnchecked(query);

    final ParameterList pl;

    if (params != null && params.count() > 0) {

      pl = pgquery.createParameterList();

      for (int i = 1; i <= params.count(); ++i) {

        final int oid = params.getOid(i);

        final Object val = params.getValue(i);

        if (val == null) {
          pl.setNull(i, oid);
          continue;
        }
        else if (val instanceof BinaryParamValue) {
          BinaryParamValue b = (BinaryParamValue) val;
          pl.setBinaryParameter(i, b.toByteArray(), b.getOid());
          continue;
        }

        switch (oid) {
          case Oid.INT4:
            pl.setIntParameter(i, (int) params.getValue(i));
            break;
          case Oid.INT4_ARRAY: {
            final int[] vals = (int[]) params.getValue(i);
            final String res = Arrays.stream(vals).mapToObj(x -> Integer.toString(x)).collect(Collectors.joining(","));
            pl.setStringParameter(i, "{" + res + "}", oid);
            break;
          }
          case Oid.INT8:
            pl.setBinaryParameter(i, Longs.toByteArray((long) params.getValue(i)), Oid.INT8);
            break;
          case Oid.UUID:
            pl.setBinaryParameter(i, (byte[]) params.getValue(i), oid);
            break;
          case Oid.BYTEA: {
            byte[] bytes = (byte[]) params.getValue(i);
            pl.setBytea(i, bytes, 0, bytes.length);
            break;
          }
          case Oid.TEXT:
          case Oid.JSON:
          case Oid.VARCHAR:
            pl.setStringParameter(i, (String) params.getValue(i), oid);
            break;
          case Oid.JSONB_ARRAY:
          case Oid.TEXT_ARRAY:
          case Oid.VARCHAR_ARRAY: {
            final StringBuilder sb = new StringBuilder();
            sb.append("{");
            int pi = 0;
            for (final String str : (String[]) params.getValue(i)) {
              if (pi++ > 0) {
                sb.append(",");
              }
              PgArray.escapeArrayElement(sb, str);
            }
            sb.append("}");
            String strval = sb.toString();
            pl.setStringParameter(i, strval, oid);
            break;
          }
          default:
            throw new AssertionError(String.format("Don't know how to map param with OID %d", oid));
        }

      }

    }
    else {

      pl = null;

    }

    // int flags = 0;

    // flags |= QueryExecutor.QUERY_ONESHOT;
    // flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;
    // flags |= QueryExecutor.QUERY_EXECUTE_AS_SIMPLE;
    // flags |= QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS;
    // flags |= QueryExecutor.QUERY_NO_RESULTS;
    // flags |= QueryExecutor.QUERY_NO_METADATA;

    // final long start = System.nanoTime();

    // query.getSubqueries().forEach(q -> log.info("Q: {}", q.sql().substring(0,
    // 16)));

    //
    try {

      if (this.pool.getListener() != null) {
        this.pool.getListener().executingQuery(this, query, params);
      }

      if (fetchSize > 0) {
        flags |= QueryExecutor.QUERY_FORWARD_CURSOR;
      }

      int fetchRows = fetchSize > 0 ? fetchSize : 0;

      PgObservableResultHandler handler = new PgObservableResultHandler(query, emitter, fetchSize);

      this.exec.execute(pgquery, pl, handler, 0, fetchRows, flags);

      fetch: while (handler.cursor != null) {

        while (emitter.requested() <= 0) {
          try {
            // urgh ... fugly.
            Thread.sleep(10);
          }
          catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          if (emitter.isCancelled()) {
            handler.cursor.close();
            break fetch;
          }
        }

        exec.fetch(handler.cursor, handler, fetchRows);

      }

    }
    finally {
      // any cleanup?
    }

    // final long stop = System.nanoTime();
    // log.info(String.format("done in %.02f ms", (stop - start) / 1000 /
    // 1000.0));

    final SQLWarning warnings = this.conn.getWarnings();

    // we should never get any warnings on the connection, as we handle them
    // ourselves.

    if (warnings != null) {
      log.debug("SQL connection warning: {}", warnings);
    }

    if (this.pool.getListener() != null) {
      this.pool.getListener().queryCompleted(this);
    }

  }

  private void copy(CopyQuery query, FlowableEmitter<QueryResult> emitter, int flags) {

    try {
      log.debug("starting COPY {}", query.command());
      long rows = conn.getCopyAPI().copyIn(query.command(), query.data(), 65536);
      log.debug("COPY complete, rows = {}", rows);
      emitter.onNext(new CommandStatus(0, "COPY", Ints.checkedCast(rows), 0));
      emitter.onComplete();
    }
    catch (SQLException | IOException e) {
      log.debug("Error performing COPY", e);
      emitter.onError(e);
    }

  }

  TransactionState transactionState() {
    return this.conn.getTransactionState();
  }

  /**
   * clean up the transaction, by aborting.
   *
   * @throws SQLException
   */

  void rollback() throws SQLException {
    log.debug("rolling back connection state");
    this.conn.rollback();
  }

  void commit() throws SQLException {
    log.debug("commiting back connection state");
    this.conn.commit();
  }

  public void notifications(final Collection<String> channels, final FlowableEmitter<NotifyMessage> emitter) {

    try {

      final CombinedQuery send = new CombinedQuery(
          channels.stream()
              .map(channel -> new SimpleQuery(String.format("LISTEN %s", this.escapeIdentifier(channel))))
              .collect(Collectors.toList()));

      Flowable.<QueryResult>create(subscribe -> this.execute(send, null, subscribe), BackpressureStrategy.BUFFER).blockingSubscribe();

      emitter.setCancellable(() -> {
        log.debug("cancelling notification");
        this.conn.cancelQuery();
      });

      while (!emitter.isCancelled()) {

        final PGNotification[] notifications = this.conn.getNotifications(1000);

        if (notifications != null) {
          emitter.onNext(new NotifyMessage(notifications));
        }

      }

    }
    catch (final SQLException e) {

      if (!emitter.tryOnError(e)) {
        log.warn("undeliverable error from notifications", e);
      }

    }

  }

  @SneakyThrows
  private String escapeIdentifier(final String channel) {
    return this.conn.escapeString(channel);
  }

  @Override
  public void blockingSet(final Map<String, String> clientProperties) {

    final CombinedQuery send = new CombinedQuery(
        clientProperties.entrySet().stream()
            .map(x -> String.format("SET %s TO %s", x.getKey(), x.getValue()))
            .map(x -> new SimpleQuery(x))
            .collect(Collectors.toList()));

    Flowable.<QueryResult>create(subscribe -> this.execute(send, null, subscribe, 0, SuppressBegin), BackpressureStrategy.BUFFER)
        .blockingForEach(x -> log.debug("set completed: {}", x));

  }

  @Override
  public void blockingExecute(final String string) {

    Flowable.<QueryResult>create(subscribe -> this.execute(new SimpleQuery(string), null, subscribe, 0, SuppressBegin), BackpressureStrategy.BUFFER)
        .map(x -> {
          if (x.getKind() == QueryResultKind.ERROR) {
            throw (ErrorResult) x;
          }
          return x;
        })
        .blockingForEach(x -> {
          log.debug("executed: {}", x);
        });

  }

  @SneakyThrows
  @Override
  public void setReadOnly(boolean b) {
    getConnection().setReadOnly(b);
  }

  @Override
  public String getServerVersion() {
    return getConnection().getServerMajorVersion() + "." + getConnection().getServerMinorVersion();
  }

}
