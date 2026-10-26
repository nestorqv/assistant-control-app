package cr.attendance.timeentry;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** CSV in one varchar column. An empty set is stored as NULL; values are written in enum order. */
@Converter
public class ReviewReasonsConverter implements AttributeConverter<Set<ReviewReason>, String> {

    @Override
    public String convertToDatabaseColumn(Set<ReviewReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        return EnumSet.copyOf(reasons).stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<ReviewReason> convertToEntityAttribute(String csv) {
        Set<ReviewReason> reasons = EnumSet.noneOf(ReviewReason.class);
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(",")).map(ReviewReason::valueOf).forEach(reasons::add);
        }
        return reasons;
    }
}
