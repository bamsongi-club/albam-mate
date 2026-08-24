package cloud.bamsongi.albammate.room.repository;

/** 자동 승격 시도 전에 읽는 현재 FIFO 첫 대기자다. */
public interface RoomWaitlistCandidateProjection {

	Long getUserId();

	Long getQueueOrder();
}
