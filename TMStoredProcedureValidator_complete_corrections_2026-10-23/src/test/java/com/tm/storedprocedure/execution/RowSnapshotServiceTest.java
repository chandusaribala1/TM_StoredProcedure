package com.tm.storedprocedure.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RowSnapshotServiceTest {
    @Test
    void detectsInsertedUpdatedAndDeletedRowsUsingOnlyFingerprintsAndKeys() {
        RowSnapshotService.RowRecord unchanged = row("1", "same");
        RowSnapshotService.RowRecord old = row("2", "before");
        RowSnapshotService.TableSnapshot before = new RowSnapshotService.TableSnapshot("public.items", java.util.List.of("id"),
                Map.of("1", unchanged, "2", old));
        RowSnapshotService.TableSnapshot after = new RowSnapshotService.TableSnapshot("public.items", java.util.List.of("id"),
                Map.of("1", unchanged, "2", row("2", "after"), "3", row("3", "new")));

        RowSnapshotService.Diff diff = RowSnapshotService.diff(before, after);

        assertThat(diff.inserted()).containsKey("3");
        assertThat(diff.updated()).containsKey("2");
        assertThat(diff.deleted()).isEmpty();
        assertThat(diff.total()).isEqualTo(2);
    }

    private static RowSnapshotService.RowRecord row(String id, String hash) {
        return new RowSnapshotService.RowRecord(hash, Map.of("id", id));
    }
}
