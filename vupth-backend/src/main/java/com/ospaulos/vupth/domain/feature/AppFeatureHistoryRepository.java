package com.ospaulos.vupth.domain.feature;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AppFeatureHistoryRepository extends JpaRepository<AppFeatureHistory, UUID> {

    List<AppFeatureHistory> findByFeatureKeyOrderByChangedAtDesc(String featureKey);
}
