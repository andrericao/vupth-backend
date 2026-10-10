package com.ospaulos.vupth.domain.notification.repository;

import com.ospaulos.vupth.domain.notification.model.PendingPushReceipt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface PendingPushReceiptRepository extends JpaRepository<PendingPushReceipt, String> {

    /** Os tickets enviados ate {@code sentBefore}, os mais antigos primeiro. */
    List<PendingPushReceipt> findBySentAtLessThanEqualOrderBySentAtAsc(OffsetDateTime sentBefore, Pageable page);

    @Transactional
    @Modifying
    @Query("delete from PendingPushReceipt r where r.ticketId in :ids")
    void deleteByTicketIdIn(Collection<String> ids);
}
