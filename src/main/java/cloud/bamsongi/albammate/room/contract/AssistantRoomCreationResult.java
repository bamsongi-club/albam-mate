package cloud.bamsongi.albammate.room.contract;

/** 확인형 Room command가 같은 트랜잭션에서 만든 식별자 결과다. */
public record AssistantRoomCreationResult(long roomId, long chatRoomId) {
}
