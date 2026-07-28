package cloud.bamsongi.albammate.room.dto;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import java.time.Instant;

/** 공개 방 목록과 상세에 공통으로 쓰는 비식별 응답이다. */
public record PublicRoomResponse(
        Long id,
        RoomType roomType,
        String title,
        String description,
        GameSummary game,
        ExperienceLevel experienceLevel,
        boolean isRulemasterLed,
        Instant startsAt,
        String region,
        int recruitmentCapacity,
        int participantCount,
        int remainingRecruitmentSeats,
        RoomStatus status,
        boolean joinable)
        implements RoomDetailResponse {}
