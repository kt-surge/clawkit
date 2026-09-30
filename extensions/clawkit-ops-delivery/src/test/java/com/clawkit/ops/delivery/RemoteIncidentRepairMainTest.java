package com.clawkit.ops.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteIncidentRepairMainTest {

    @Test
    void standaloneRemoteRepairEntryIsFailClosedEvenWhenAutoApproveIsRequested() {
        assertThat(RemoteIncidentRepairMain.run(new String[] {
            "--target", "real-server", "--auto-approve"
        })).isEqualTo(2);
    }
}
