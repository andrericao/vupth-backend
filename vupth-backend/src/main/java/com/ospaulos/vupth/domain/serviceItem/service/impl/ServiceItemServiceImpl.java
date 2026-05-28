package com.ospaulos.vupth.domain.serviceItem.service.impl;

import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ServiceItemServiceImpl implements ServiceItemService {

    private final ProfessionalRepository professionalRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ServiceItemRepository serviceItemRepository;
    private final AttendanceRepository attendanceRepository;

    @Override
    @Transactional
    public ServiceItemResponse createServiceItem(Professional professional, ServiceItemRequest request) {

        ServiceItem serviceItem = new ServiceItem();
        serviceItem.setServiceItemType(request.serviceItemType());
        serviceItem.setCustomName(request.customName());
        serviceItem.setPrice(request.price());
        serviceItem.setEstimatedMinutes(request.estimatedMinutes());
        serviceItem.setProfessional(professional);

        ServiceItem saved = serviceItemRepository.save(serviceItem);

        return ServiceItemResponse.fromEntity(saved);
    }

    public double avarageCalculateLastWeek(UUID serviceItemId) {
        LocalDateTime startOfWeek = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        List<Attendance> attendances = attendanceRepository.findByServiceItems_IdAndStatusAndCreatedAtAfter(
                serviceItemId,
                AttendanceStatus.FINISHED,
                startOfWeek
        );

        // Only considers attendances with a recorded duration
        List<Integer> durations = attendances.stream()
                .map(Attendance::getDurationInMinutes)
                .filter(Objects::nonNull)
                .toList();

        if (durations.isEmpty()) return 0;

        return durations.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    /**
     * Returns service duration (uses weekly average if available)
     */
    private int getServiceDuration(Attendance attendance) {
        int totalEstimatedMinutes = 0;

        for (ServiceItem service : attendance.getServiceItems()) {
            // Prefer weekly average duration if available
            if (service.getWeeklyAverageDurationInMinutes() != null &&
                    service.getWeeklyAverageDurationInMinutes() > 0) {
                totalEstimatedMinutes += service.getWeeklyAverageDurationInMinutes();
            } else {
                totalEstimatedMinutes += service.getEstimatedMinutes() != null ?
                        service.getEstimatedMinutes() : 30; // Default 30min
            }
        }

        return totalEstimatedMinutes;
    }
}