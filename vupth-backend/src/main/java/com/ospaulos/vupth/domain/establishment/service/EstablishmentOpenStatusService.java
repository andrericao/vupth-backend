package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;

import java.util.UUID;

public interface EstablishmentOpenStatusService {

    public void updateEstablishmentOpenStatus(UUID establishmentId, OpenStatus newStatus);

    public boolean isEstablishmentOpen(UUID establishmentId);

    //private boolean isWithinBusinessHours(String businessHours);

    public void updateAllEstablishmentsStatus();
}
