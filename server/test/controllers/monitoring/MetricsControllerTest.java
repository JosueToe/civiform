package controllers.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static play.test.Helpers.contentAsString;

import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import controllers.WithMockedProfiles;
import io.prometheus.client.CollectorRegistry;
import java.util.Locale;
import models.Applicant;
import models.Application;
import models.LifecycleStage;
import org.junit.Before;
import org.junit.Test;
import play.mvc.Result;
import repository.VersionRepository;
import services.program.ProgramDefinition;
import support.ProgramBuilder;

public class MetricsControllerTest extends WithMockedProfiles {
  @Before
  public void setUp() {
    resetDatabase();
    CollectorRegistry.defaultRegistry.clear();
    MetricsController.initializeCounters();
  }

  @Test
  public void getMetrics_returnsMetricData() {
    Config config =
        ConfigFactory.parseMap(
            ImmutableMap.<String, String>builder().put("server_metrics.enabled", "true").build());
    MetricsController controllerWithMetricsEnabled = new MetricsController(config);

    ProgramDefinition programDefinition =
        ProgramBuilder.newActiveProgram("test program", "desc").buildDefinition();
    VersionRepository versionRepository = instanceOf(VersionRepository.class);
    Applicant applicant = createApplicantWithMockedProfile();
    applicant.getApplicantData().setPreferredLocale(Locale.ENGLISH);
    applicant.save();
    Application app =
        new Application(applicant, programDefinition.toProgram(), LifecycleStage.DRAFT);
    app.save();
    resourceCreator().insertDraftProgram(programDefinition.adminName());
    versionRepository.publishNewSynchronizedVersion();

    Result metricsResult = controllerWithMetricsEnabled.getMetrics();

    String metricsContent = contentAsString(controllerWithMetricsEnabled.getMetrics());

    assertThat(metricsResult.status()).isEqualTo(200);
    assertThat(metricsContent).contains("ebean_queries_mean_latency_micros");
    assertThat(metricsContent).contains("ebean_queries_max_latency_micros");
    assertThat(metricsContent).contains("ebean_queries_total_latency_micros");
    assertThat(metricsContent).contains(getEbeanCountName("Program.findList"));
    assertThat(metricsContent).contains(getEbeanCountName("Question.findList"));
    assertThat(metricsContent).contains(getEbeanCountName("Version.byId"));
  }

  @Test
  public void getMetrics_returns404WhenMetricsNotEnabled() {
    Config config =
        ConfigFactory.parseMap(
            ImmutableMap.<String, String>builder().put("server_metrics.enabled", "false").build());
    MetricsController controllerWithoutMetricsEnabled = new MetricsController(config);
    assertThat(controllerWithoutMetricsEnabled.getMetrics().status()).isEqualTo(404);
  }

  private String getEbeanCountName(String queryName) {
    return String.format("ebean_queries_total{name=\"%s\",}", queryName);
  }
}