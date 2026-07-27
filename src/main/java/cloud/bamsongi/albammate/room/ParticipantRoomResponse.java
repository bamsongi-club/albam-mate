package cloud.bamsongi.albammate.room;

import cloud.bamsongi.albammate.game.GameSummary;
import cloud.bamsongi.albammate.room.entity.ExperienceLevel;
import cloud.bamsongi.albammate.room.entity.RoomStatus;
import cloud.bamsongi.albammate.room.entity.RoomType;
import java.time.Instant;
import java.util.List;

/** 주최자 또는 현재 활성 참가자에게 반환하는 방 상세 표현이다. */
public record ParticipantRoomResponse(
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
        boolean joinable,
        MyRole myRole,
        String place,
        NicknameSummary host,
        List<NicknameSummary> participants) {}
