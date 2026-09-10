package io.cafeai.sentinel;

import io.cafeai.sentinel.watch.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterWatchTest {

    @Test
    void emptyStatusYieldsNoContainers() {
        assertThat(ClusterWatch.containerStates(null)).isEmpty();
        assertThat(ClusterWatch.containerStates(new PodStatusBuilder().build())).isEmpty();
    }

    @Test
    void runningContainerIsNotTroubled() {
        PodStatus status = new PodStatusBuilder()
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withName("api")
                        .withReady(true)
                        .withRestartCount(0)
                        .withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
                        .build())
                .build();

        ContainerState api = ClusterWatch.containerStates(status).get(0);

        assertThat(api.state()).isEqualTo("running");
        assertThat(api.troubled()).isFalse();
        assertThat(api.reason()).isNull();
    }

    @Test
    void crashLoopCarriesExitCodeUpFromLastState() {
        // Current state is "waiting/CrashLoopBackOff"; the real cause (exit 1)
        // is only in lastState.terminated — it must still surface.
        PodStatus status = new PodStatusBuilder()
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withName("api")
                        .withReady(false)
                        .withRestartCount(4)
                        .withState(new ContainerStateBuilder()
                                .withNewWaiting().withReason("CrashLoopBackOff").withMessage("back-off 40s").endWaiting()
                                .build())
                        .withLastState(new ContainerStateBuilder()
                                .withNewTerminated().withReason("Error").withExitCode(1).endTerminated()
                                .build())
                        .build())
                .build();

        ContainerState api = ClusterWatch.containerStates(status).get(0);

        assertThat(api.state()).isEqualTo("waiting");
        assertThat(api.reason()).isEqualTo("CrashLoopBackOff");
        assertThat(api.lastTerminationReason()).isEqualTo("Error");
        assertThat(api.restartCount()).isEqualTo(4);
        assertThat(api.exitCode()).isEqualTo(1);
        assertThat(api.troubled()).isTrue();
    }

    @Test
    void oomKilledSurfacesFromLastStateWhileBackingOff() {
        // OOMKilled never appears as an Event — only here.
        PodStatus status = new PodStatusBuilder()
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withName("worker")
                        .withReady(false)
                        .withRestartCount(2)
                        .withState(new ContainerStateBuilder()
                                .withNewWaiting().withReason("CrashLoopBackOff").endWaiting()
                                .build())
                        .withLastState(new ContainerStateBuilder()
                                .withNewTerminated().withReason("OOMKilled").withExitCode(137).endTerminated()
                                .build())
                        .build())
                .build();

        ContainerState worker = ClusterWatch.containerStates(status).get(0);

        assertThat(worker.reason()).isEqualTo("CrashLoopBackOff");
        assertThat(worker.lastTerminationReason()).isEqualTo("OOMKilled");
        assertThat(worker.exitCode()).isEqualTo(137);
    }

    @Test
    void imagePullBackOffReadsFromWaitingState() {
        PodStatus status = new PodStatusBuilder()
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withName("app")
                        .withReady(false)
                        .withRestartCount(0)
                        .withState(new ContainerStateBuilder()
                                .withNewWaiting().withReason("ImagePullBackOff")
                                .withMessage("Back-off pulling image \"busybox:nope\"").endWaiting()
                                .build())
                        .build())
                .build();

        List<ContainerState> containers = ClusterWatch.containerStates(status);
        ContainerState app = containers.get(0);

        assertThat(app.reason()).isEqualTo("ImagePullBackOff");
        assertThat(app.lastTerminationReason()).isNull();
        assertThat(app.exitCode()).isNull();
        assertThat(app.troubled()).isTrue();
    }
}
