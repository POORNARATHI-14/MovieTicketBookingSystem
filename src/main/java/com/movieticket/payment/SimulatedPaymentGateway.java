package com.movieticket.payment;

import com.movieticket.config.AppProperties;
import com.movieticket.payment.GatewayResult.Outcome;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SIMULATION ONLY. No card data is read and no money moves. The result is chosen by the requested
 * {@link PaymentMethod} "test card" so every Saga path can be demonstrated deterministically.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final AppProperties.Simulator config;

    public SimulatedPaymentGateway(AppProperties props) {
        this.config = props.payment().simulator();
    }

    @Override
    public GatewayResult charge(Long bookingId, BigDecimal amount, PaymentMethod method) {
        simulateLatency();
        GatewayResult result = switch (method) {
            case TEST_SUCCESS, TEST_CAPTURE_THEN_FAIL -> success();
            case TEST_DECLINED -> new GatewayResult(Outcome.DECLINED, null, "Card declined (simulated)");
            case TEST_TIMEOUT -> new GatewayResult(Outcome.TIMEOUT, null, "Gateway timed out (simulated)");
            case TEST_RANDOM -> ThreadLocalRandom.current().nextDouble() < config.successRate()
                    ? success()
                    : new GatewayResult(Outcome.DECLINED, null, "Random decline (simulated)");
        };
        log.info("[SIMULATED GATEWAY] charge booking={} amount={} method={} -> {}", bookingId, amount, method, result.outcome());
        return result;
    }

    @Override
    public GatewayResult refund(String gatewayReference, BigDecimal amount) {
        simulateLatency();
        log.info("[SIMULATED GATEWAY] refund reference={} amount={} -> SUCCESS", gatewayReference, amount);
        return new GatewayResult(Outcome.SUCCESS, "SIM-RF-" + UUID.randomUUID().toString().substring(0, 8), "Refunded (simulated)");
    }

    private GatewayResult success() {
        return new GatewayResult(Outcome.SUCCESS, "SIM-" + UUID.randomUUID().toString().substring(0, 12), "Approved (simulated)");
    }

    private void simulateLatency() {
        if (config.latencyMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(config.latencyMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
