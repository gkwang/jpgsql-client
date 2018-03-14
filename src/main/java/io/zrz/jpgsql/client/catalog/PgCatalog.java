package io.zrz.jpgsql.client.catalog;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.zrz.jpgsql.client.PostgresQueryProcessor;
import io.zrz.jpgsql.client.PostgresUtils;
import io.zrz.sqlwriter.DbIdent;
import io.zrz.sqlwriter.QueryGenerator;

public class PgCatalog {

  private PostgresQueryProcessor pg;

  private Flowable<PgClassRecord> catalog = Flowable.defer(() -> QueryGenerator.from(DbIdent.of("pg_catalog", "pg_class")).submitTo(pg))
      .flatMap(PostgresUtils.rowMapper())
      .map(row -> new PgClassRecord(row))
      .cache();

  public PgCatalog(PostgresQueryProcessor pg) {
    this.pg = pg;
  }

  public Completable loadCatalog() {
    return catalog.ignoreElements();
  }

  public Single<Boolean> exists(DbIdent klass) {
    return catalog
        .filter(x -> x.getSimpleName().equals(klass.getSimpleName()))
        .map(x -> true)
        .single(false);
  }

  public Flowable<PgClassRecord> catalog() {
    return this.catalog;
  }

}
