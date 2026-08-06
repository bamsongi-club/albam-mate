package cloud.bamsongi.albammate.room.contract;

import java.util.Collection;

/** ROOM 변경과 같은 트랜잭션에서 수신자 스냅샷 및 Outbox를 기록하는 포트다. */
public interface RoomChangeEventRecorder {

	void record(RoomChangeEvent event, Collection<Long> recipientUserIds);
}
