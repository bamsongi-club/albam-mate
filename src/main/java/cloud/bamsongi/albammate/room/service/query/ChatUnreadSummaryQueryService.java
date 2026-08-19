package cloud.bamsongi.albammate.room.service.query;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.chat.contract.ChatRoomPreviewQuery;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;

/** 상단 채팅 아이콘 배지용 미읽음 방 개수(CHAT-07)를 조립한다. */
@Service
public class ChatUnreadSummaryQueryService {

	private final MyRoomReadService myRoomReadService;
	private final ChatRoomPreviewQuery chatRoomPreviewQuery;
	private final Clock clock;

	public ChatUnreadSummaryQueryService(
		MyRoomReadService myRoomReadService, ChatRoomPreviewQuery chatRoomPreviewQuery, Clock clock) {
		this.myRoomReadService = Objects.requireNonNull(myRoomReadService, "myRoomReadService");
		this.chatRoomPreviewQuery = Objects.requireNonNull(chatRoomPreviewQuery, "chatRoomPreviewQuery");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/** 현재 사용자가 참가·주최한 chatAvailable 방 중 미읽음 메시지가 1건 이상인 방의 개수를 반환한다. */
	public int countUnreadRooms(Long currentUserId) {
		Instant requestTime = Instant.now(clock);
		MyRoomReadService.MyRoomReadResult readResult = myRoomReadService.findMyRoomsAt(
			currentUserId, MyRoomRole.ALL, Pageable.unpaged(), requestTime);

		Set<Long> chatAvailableRoomIds = readResult.rooms().stream()
			.filter(room -> readResult.effectiveStatusFor(room).isChatAvailable())
			.map(Room::getId)
			.collect(Collectors.toUnmodifiableSet());
		if (chatAvailableRoomIds.isEmpty()) {
			return 0;
		}

		Map<Long, ChatRoomPreviewQuery.ChatRoomPreview> previews = chatRoomPreviewQuery
			.findPreviews(currentUserId, chatAvailableRoomIds);
		return (int)previews.values().stream()
			.filter(preview -> preview.unreadCount() >= 1)
			.count();
	}
}
