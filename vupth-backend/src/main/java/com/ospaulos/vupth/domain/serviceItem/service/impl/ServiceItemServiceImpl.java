package com.ospaulos.vupth.domain.serviceItem.service.impl;

import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.UpdateServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemAlreadyExistsException;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemNotFoundException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
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
        serviceItem.setClientPrice(request.clientPrice());
        serviceItem.setUsesPlatformPricing(request.usesPlatformPricing());
        serviceItem.setEstimatedMinutes(request.estimatedMinutes());
        serviceItem.setProfessional(professional);

        ServiceItem saved = serviceItemRepository.save(serviceItem);

        return ServiceItemResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public ServiceItemResponse updateServiceItem(UUID requesterId, UUID professionalId, UUID itemId,
                                                 UpdateServiceItemRequest request) {
        log.info("[ServiceItem] update — requester={} professional={} item={}", requesterId, professionalId, itemId);

        ServiceItem item = loadOwnedItem(requesterId, professionalId, itemId);

        applyUpdate(item, request);

        return ServiceItemResponse.fromEntity(serviceItemRepository.save(item));
    }

    @Override
    @Transactional
    public void deleteServiceItem(UUID requesterId, UUID professionalId, UUID itemId) {
        log.info("[ServiceItem] delete — requester={} professional={} item={}", requesterId, professionalId, itemId);

        ServiceItem item = loadOwnedItem(requesterId, professionalId, itemId);
        serviceItemRepository.delete(item);
    }

    @Override
    @Transactional
    public ServiceItemResponse updateServiceItemForEstablishment(UUID requesterId, UUID establishmentId, UUID itemId,
                                                                 UpdateServiceItemRequest request) {
        log.info("[ServiceItem] update (establishment) — requester={} establishment={} item={}",
                requesterId, establishmentId, itemId);

        ServiceItem item = loadEstablishmentItem(requesterId, establishmentId, itemId);

        // Ao renomear, mantem a unicidade de nome dentro do establishment (mesma regra do cadastro).
        if (request.customName() != null && !request.customName().isBlank()) {
            String newName = request.customName().trim();
            boolean clash = serviceItemRepository.findAllByEstablishmentId(establishmentId).stream()
                    .anyMatch(s -> !s.getId().equals(itemId) && s.getCustomName().equalsIgnoreCase(newName));
            if (clash) {
                throw new ServiceItemAlreadyExistsException(newName);
            }
        }

        applyUpdate(item, request);

        return ServiceItemResponse.fromEntity(serviceItemRepository.save(item));
    }

    @Override
    @Transactional
    public void deleteServiceItemForEstablishment(UUID requesterId, UUID establishmentId, UUID itemId) {
        log.info("[ServiceItem] delete (establishment) — requester={} establishment={} item={}",
                requesterId, establishmentId, itemId);

        ServiceItem item = loadEstablishmentItem(requesterId, establishmentId, itemId);
        serviceItemRepository.delete(item);
    }

    /** Aplica os campos parciais (null/branco ignorados) no ServiceItem. */
    private void applyUpdate(ServiceItem item, UpdateServiceItemRequest request) {
        if (request.customName() != null && !request.customName().isBlank()) {
            item.setCustomName(request.customName().trim());
        }
        if (request.price() != null) {
            item.setPrice(request.price());
        }
        if (request.estimatedMinutes() != null) {
            item.setEstimatedMinutes(request.estimatedMinutes());
        }
        if (request.clientPrice() != null) {
            item.setClientPrice(request.clientPrice());
        }
        if (request.usesPlatformPricing() != null) {
            item.setUsesPlatformPricing(request.usesPlatformPricing());
        }
    }

    /**
     * Carrega o ServiceItem garantindo que pertence ao profissional autenticado.
     * O requester (JWT) tem de ser o proprio profissional dono do item.
     */
    private ServiceItem loadOwnedItem(UUID requesterId, UUID professionalId, UUID itemId) {
        if (!requesterId.equals(professionalId)) {
            throw new EstablishmentAccessDeniedException(
                    "Professional " + requesterId + " cannot manage service items of " + professionalId);
        }
        ServiceItem item = serviceItemRepository.findById(itemId)
                .orElseThrow(() -> new ServiceItemNotFoundException(itemId));
        if (item.getProfessional() == null || !item.getProfessional().getId().equals(requesterId)) {
            throw new EstablishmentAccessDeniedException(
                    "Service item " + itemId + " does not belong to professional " + requesterId);
        }
        return item;
    }

    /**
     * Carrega o ServiceItem pela otica do establishment.
     * O item tem de pertencer a um profissional afiliado ao establishment informado (senao 404).
     * So o owner do establishment OU o proprio profissional dono do item podem gerencia-lo (senao 403).
     */
    private ServiceItem loadEstablishmentItem(UUID requesterId, UUID establishmentId, UUID itemId) {
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        ServiceItem item = serviceItemRepository.findById(itemId)
                .orElseThrow(() -> new ServiceItemNotFoundException(itemId));

        Professional itemProfessional = item.getProfessional();
        boolean belongsToEstablishment = itemProfessional != null
                && itemProfessional.getEstablishment() != null
                && itemProfessional.getEstablishment().getId().equals(establishmentId);
        if (!belongsToEstablishment) {
            // Item existe mas nao faz parte deste establishment — trata como nao encontrado nesse escopo.
            throw new ServiceItemNotFoundException(itemId);
        }

        boolean isOwner = establishment.getOwner() != null
                && establishment.getOwner().getId().equals(requesterId);
        boolean isItemProfessional = itemProfessional.getId().equals(requesterId);
        if (!isOwner && !isItemProfessional) {
            throw new EstablishmentAccessDeniedException(
                    "User " + requesterId + " cannot manage service item " + itemId
                            + " of establishment " + establishmentId);
        }
        return item;
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