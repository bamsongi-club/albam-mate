package cloud.bamsongi.albammate.room.contract;

/** assistant가 확인된 초안을 Room 생성으로 전환하는 유일한 공개 port다. */
public interface AssistantRoomCommand {

	AssistantRoomCreationResult createConfirmedRoom(AssistantRoomCreationCommand command);
}
