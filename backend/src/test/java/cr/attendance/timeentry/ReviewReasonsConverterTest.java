package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReviewReasonsConverterTest {

    private final ReviewReasonsConverter converter = new ReviewReasonsConverter();

    @Test
    void emptySetIsNullAndNullIsEmptySet() {
        assertThat(converter.convertToDatabaseColumn(EnumSet.noneOf(ReviewReason.class))).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }

    @Test
    void valuesAreWrittenInEnumOrder() {
        Set<ReviewReason> scrambled = new LinkedHashSet<>();
        scrambled.add(ReviewReason.SHIFT_TOO_LONG);
        scrambled.add(ReviewReason.NO_GPS);
        scrambled.add(ReviewReason.SYNCED_LATE);
        assertThat(converter.convertToDatabaseColumn(scrambled)).isEqualTo("NO_GPS,SYNCED_LATE,SHIFT_TOO_LONG");
    }

    @Test
    void roundTrip() {
        Set<ReviewReason> all = EnumSet.allOf(ReviewReason.class);
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(all))).isEqualTo(all);
    }
}
