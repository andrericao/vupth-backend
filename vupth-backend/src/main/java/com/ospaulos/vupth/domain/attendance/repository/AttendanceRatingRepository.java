package com.ospaulos.vupth.domain.attendance.repository;

import com.ospaulos.vupth.domain.attendance.model.AttendanceRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AttendanceRatingRepository extends JpaRepository<AttendanceRating, UUID> {

    /** True se o atendimento ja foi avaliado (bloqueia o 2o voto — item #6). */
    boolean existsByAttendanceId(UUID attendanceId);
}
