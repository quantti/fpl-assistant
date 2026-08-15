package dev.quantti.fplassistant.price;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests against a hand-built {@code merged_gw.csv} fragment. The real file has
 * dozens of columns; only {@code element}, {@code round}, {@code value} and {@code name} are
 * read, by header name.
 */
class MergedGwParserTest {

    private static final String SAMPLE = """
            name,position,element,round,value,total_points
            Salah,MID,302,1,140,12
            Haaland,FWD,423,1,150,16
            Salah,MID,302,2,142,8
            """;

    @Test
    void parsesAllRows() {
        List<MergedGwRow> rows = MergedGwParser.parse(SAMPLE, "2026-27");
        assertThat(rows).hasSize(3);
    }

    @Test
    void readsElementGameweekPriceAndName() {
        List<MergedGwRow> rows = MergedGwParser.parse(SAMPLE, "2026-27");
        MergedGwRow first = rows.getFirst();
        assertThat(first.elementId()).isEqualTo(302);
        assertThat(first.gameweek()).isEqualTo(1);
        assertThat(first.price()).isEqualTo(140);
        assertThat(first.name()).isEqualTo("Salah");
    }

    @Test
    void acceptsWholeNumberFloatsButRejectsFractionalPrices() {
        // vaastav sometimes writes whole-number floats ("140.0") — accept those, but reject a
        // real fractional value, same fail-loud class as VaastavFixtureParser's score parsing.
        String whole = "name,element,round,value\nSalah,302,1,140.0";
        assertThat(MergedGwParser.parse(whole, "2026-27").getFirst().price()).isEqualTo(140);

        String fractional = "name,element,round,value\nSalah,302,1,140.5";
        assertThatThrownBy(() -> MergedGwParser.parse(fractional, "2026-27"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-integer");
    }

    @Test
    void missingRequiredColumnFailsLoudly() {
        String noValue = "name,element,round\nSalah,302,1";
        assertThatThrownBy(() -> MergedGwParser.parse(noValue, "2026-27"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("value");
    }
}
