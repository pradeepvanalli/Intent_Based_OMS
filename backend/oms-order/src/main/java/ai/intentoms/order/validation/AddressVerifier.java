package ai.intentoms.order.validation;

import ai.intentoms.order.capture.CaptureRequest;

/**
 * Address verification SPI (FR-CAP-05). {@link MockAddressVerifier} is the default; a tenant plugs a real provider
 * (SmartyStreets, Loqate, USPS) by registering another bean.
 */
public interface AddressVerifier {
    AddressVerification verify(CaptureRequest.AddressInput address);
}
