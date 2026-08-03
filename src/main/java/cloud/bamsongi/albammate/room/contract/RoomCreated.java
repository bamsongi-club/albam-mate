package cloud.bamsongi.albammate.room.contract;

/** ROOM 생성 트랜잭션 안에서 발행하는 새 방 생성 사실이다. */
public record RoomCreated(long roomId) {
}
