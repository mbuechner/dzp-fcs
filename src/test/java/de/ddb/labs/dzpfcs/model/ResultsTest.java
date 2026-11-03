package de.ddb.labs.dzpfcs.model;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultsTest {

    @Test
    void protectsPageOrderAndSizeFromChangesToTheInputOrReturnedList() {
        ResultsEntry first = new ResultsEntry();
        first.setId("first");
        ResultsEntry second = new ResultsEntry();
        second.setId("second");
        List<ResultsEntry> input = new ArrayList<>(List.of(first, second));
        Results page = new Results("corpus", "query", input, 53, 40);

        input.clear();

        assertEquals(List.of("first", "second"), page.getResults().stream().map(ResultsEntry::getId).toList());
        assertThrows(UnsupportedOperationException.class, () -> page.getResults().clear());
        assertEquals(2, page.getResults().size());
        assertEquals(53, page.getTotal());
        assertEquals(40, page.getOffset());
    }
}
