package io.zrz.jpgsql.proto.wire;

import lombok.Value;

@Value
public class ReadyForQuery implements PostgreSQLPacket {

  private final TransactionStatus status;

  @Override
  public <T> T apply(final PostgreSQLPacketVisitor<T> visitor) {
    return visitor.visitReadyForQuery(this);
  }

}
