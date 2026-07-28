package cloud.bamsongi.albammate.room.dto;

import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;

public record RoomParticipationResponse(
        Long roomId,
        ParticipationStatus participationStatus,
        RoomStatus roomStatus,
        int participantCount,
        int remainingRecruitmentSeats) {}
