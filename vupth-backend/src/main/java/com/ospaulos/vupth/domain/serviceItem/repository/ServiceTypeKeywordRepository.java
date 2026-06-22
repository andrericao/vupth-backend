package com.ospaulos.vupth.domain.serviceItem.repository;

import com.ospaulos.vupth.domain.serviceItem.model.ServiceTypeKeyword;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceTypeKeywordRepository extends JpaRepository<ServiceTypeKeyword, UUID> {

    List<ServiceTypeKeyword> findByServiceItemType(ServiceItemType serviceItemType);

    boolean existsByServiceItemTypeAndKeyword(ServiceItemType serviceItemType, String keyword);
}
