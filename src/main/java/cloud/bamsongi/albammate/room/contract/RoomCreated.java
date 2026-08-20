package cloud.bamsongi.albammate.room.contract;

/** ROOM 생성 트랜잭션 안에서 발행하는 새 방 생성 사실이다. */
public final class RoomCreated {

	private final long roomId;
	private Long chatRoomId;

	public RoomCreated(long roomId) {
		this.roomId = roomId;
	}

	public long roomId() {
		return roomId;
	}

	/** 동기 CHAT handoff가 같은 트랜잭션 안에서 결과 식별자를 채운다. */
	public void completeChatRoom(long chatRoomId) {
		this.chatRoomId = chatRoomId;
	}

	public long requireChatRoomId() {
		if (chatRoomId == null) {
			throw new IllegalStateException("RoomCreated chat room handoff was not completed");
		}
		return chatRoomId;
	}
}
