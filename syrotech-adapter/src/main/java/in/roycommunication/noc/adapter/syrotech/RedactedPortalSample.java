package in.roycommunication.noc.adapter.syrotech;

import in.roycommunication.noc.adapter.syrotech.SyrotechFieldReplay.LogRow;
import in.roycommunication.noc.adapter.syrotech.SyrotechFieldReplay.Onu;
import in.roycommunication.noc.adapter.syrotech.SyrotechFieldReplay.StatusRow;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The bundled, manually transcribed ONU Status and System Log tables from a single PON. */
public final class RedactedPortalSample {
    private static final DateTimeFormatter OLT_LOCAL = DateTimeFormatter
            .ofPattern("uuuu/MM/dd HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private RedactedPortalSample() {}

    public static SyrotechFieldReplay.Result replay() throws IOException {
        var log = table("system-log.csv", "tableRow,oltLocalTime,pon,onu,event,diagnostic").stream()
                .map(c -> new LogRow(Integer.parseInt(c[0]), LocalDateTime.parse(c[1], OLT_LOCAL),
                        new Onu(c[2], c[3]), c[4], c[5])).toList();
        var status = table("onu-status.csv", "pon,onu,status,lastRegister,lastDeregister,lastDeregisterReason").stream()
                .map(c -> new StatusRow(new Onu(c[0], c[1]), c[2], historicalTime(c[3]), historicalTime(c[4]), c[5]))
                .toList();
        return SyrotechFieldReplay.replay(log, status, Duration.ofSeconds(2));
    }

    private static LocalDateTime historicalTime(String cell) {
        return cell.equals("N/A") ? null : LocalDateTime.parse(cell, OLT_LOCAL);
    }

    // These bundled fixtures contain a fixed set of comma-free fields selected from the portal.
    private static List<String[]> table(String file, String header) throws IOException {
        var input = RedactedPortalSample.class.getResourceAsStream("/syrotech/field/" + file);
        if (input == null) throw new FileNotFoundException(file);
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            if (!header.equals(reader.readLine())) throw new IOException("Unexpected header in " + file);
            int columns = header.split(",").length;
            var rows = new ArrayList<String[]>();
            for (String line; (line = reader.readLine()) != null; ) {
                String[] cells = line.split(",", -1);
                if (cells.length != columns) throw new IOException("Unexpected column count in " + file);
                rows.add(cells);
            }
            return rows;
        }
    }

    public static void main(String[] args) throws IOException {
        var capture = replay();
        System.out.println("System Log (OLT-local time), oldest first");
        for (var entry : capture.journal()) {
            var row = entry.source();
            System.out.printf("%s  %s/%s  %s  %s  [%s]%n", OLT_LOCAL.format(row.oltLocalTime()),
                    row.onu().pon(), row.onu().id(), row.event(), row.diagnostic(),
                    entry.transition().map(a -> a.type().name()).orElse("journal only"));
        }
        System.out.println("ONU Status (current status, last deregistration reason, replay state)");
        for (var reading : capture.statuses()) {
            var row = reading.source();
            System.out.printf("%s/%s  %s  %s  [%s]%n", row.onu().pon(), row.onu().id(),
                    row.status(), row.lastDeregisterReason(), reading.state());
        }
        System.out.printf("Power-loss episodes: %d%n", capture.powerEpisodes().size());
    }
}
