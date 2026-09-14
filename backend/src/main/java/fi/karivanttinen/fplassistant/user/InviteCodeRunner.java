package fi.karivanttinen.fplassistant.user;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Issues and inspects invite codes from the command line, because there is no admin surface and
 * a code is issued a handful of times a season.
 *
 * <pre>
 * --create-invite-code=launch-2026:10
 * --create-invite-code=podcast-xyz:100:discount_percent:50
 * --list-invite-codes
 * --reconcile-invite-codes
 * </pre>
 *
 * The argument is {@code code:maxUses[:benefitKind[:benefitValue]]}. {@code maxUses} may be
 * {@code unlimited}, in which case only the account cap limits it. Creating a code that already
 * exists does nothing and says so: re-running a command must never reset a live code's ceiling.
 */
@Component
public class InviteCodeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeRunner.class);

    private final InviteLedger ledger;

    public InviteCodeRunner(InviteLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean acted = false;
        if (args.containsOption("create-invite-code")) {
            for (String spec : args.getOptionValues("create-invite-code")) {
                create(spec);
            }
            acted = true;
        }
        if (args.containsOption("reconcile-invite-codes")) {
            List<String> drifted = ledger.reconcile();
            log.info("reconciled invite codes; {} had drifted{}", drifted.size(),
                    drifted.isEmpty() ? "" : ": " + String.join(", ", drifted));
            acted = true;
        }
        if (args.containsOption("list-invite-codes") || acted) {
            list();
            acted = true;
        }
        if (acted) {
            System.exit(0);
        }
    }

    private void create(String spec) {
        String[] parts = spec.split(":");
        if (parts.length < 2) {
            log.error("--create-invite-code={} is not code:maxUses[:benefitKind[:benefitValue]]",
                    spec);
            return;
        }
        String code = parts[0];
        Integer maxUses = "unlimited".equalsIgnoreCase(parts[1]) ? null : parseInt(parts[1]);
        if (!"unlimited".equalsIgnoreCase(parts[1]) && maxUses == null) {
            log.error("--create-invite-code={}: {} is not a number of places", spec, parts[1]);
            return;
        }
        String benefitKind = parts.length > 2 ? parts[2] : InviteCode.ACCESS;
        Integer benefitValue = parts.length > 3 ? parseInt(parts[3]) : null;
        if (InviteCode.DISCOUNT_PERCENT.equals(benefitKind)
                && (benefitValue == null || benefitValue < 1 || benefitValue > 100)) {
            log.error("--create-invite-code={}: a discount code needs a percentage of 1-100", spec);
            return;
        }

        Optional<InviteCode> created = ledger.create(code, code, maxUses, benefitKind,
                benefitValue, null, null, null);
        if (created.isEmpty()) {
            log.warn("invite code {} already exists; left untouched", code);
            return;
        }
        log.info("created invite code {} ({} place(s), {})", code,
                maxUses == null ? "unlimited" : maxUses, describe(created.get()));
    }

    private void list() {
        List<InviteCode> codes = ledger.all();
        if (codes.isEmpty()) {
            log.info("no invite codes exist; sign-up refuses everyone until one is created");
            return;
        }
        for (InviteCode code : codes) {
            log.info("{} | {} used of {} | {} | {}", code.code(), code.uses(),
                    code.maxUses() == null ? "unlimited" : code.maxUses(),
                    describe(code), code.active() ? "active" : "inactive");
        }
    }

    private static String describe(InviteCode code) {
        return InviteCode.DISCOUNT_PERCENT.equals(code.benefitKind())
                ? code.benefitValue() + "% off" : "access";
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
