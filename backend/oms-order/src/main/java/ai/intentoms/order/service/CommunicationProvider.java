package ai.intentoms.order.service;

import ai.intentoms.order.model.Communication;

import java.util.List;

/** SPI for customer communications shown on the order view (FR-CS-01); supplied by the CS/returns modules. Default: none. */
public interface CommunicationProvider {
    List<Communication> communicationsFor(String orderId);

    static CommunicationProvider none() { return orderId -> List.of(); }
}
