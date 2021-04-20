package app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static play.test.Helpers.fakeApplication;

import auth.Roles;
import com.google.common.collect.ImmutableMap;
import controllers.routes;
import java.util.Optional;
import models.Applicant;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import play.Application;
import repository.ApplicantRepository;
import services.WellKnownPaths;
import support.TestConstants;

public class SecurityBrowserTest extends BaseBrowserTest {
  public static final DockerImageName OIDC_IMAGE =
      DockerImageName.parse("public.ecr.aws/t1q6b4h2/oidc-provider:latest");

  @ClassRule
  public static GenericContainer<?> oidcProvider =
      new GenericContainer<>(OIDC_IMAGE).withExposedPorts(3380);

  private static ApplicantRepository applicantRepository;

  @Override
  protected Application provideApplication() {
    ImmutableMap<String, Object> config =
        new ImmutableMap.Builder<String, Object>()
            .putAll(TestConstants.TEST_DATABASE_CONFIG)
            .putAll(
                TestConstants.oidcConfig(oidcProvider.getHost(), oidcProvider.getMappedPort(3380)))
            .build();
    return fakeApplication(config);
  }

  @Before
  public void getApplicantRepository() {
    applicantRepository = app.injector().instanceOf(ApplicantRepository.class);
  }

  protected void loginWithSimulatedIdcs() {
    goTo(routes.LoginController.idcsLogin());
    // If we are not cookied, enter a username and password.
    if (browser.pageSource().contains("Enter any login")) {
      browser.$("[name='login']").click();
      browser.keyboard().sendKeys("username");
      browser.$("[name='password']").click();
      browser.keyboard().sendKeys("password");
      // Log in.
      browser.$(".login-submit").click();
      // Bypass consent screen.
      browser.$(".login-submit").click();
    } else {
      browser.$(".login-submit").click();
    }
  }

  @Test
  public void homePage_whenNotLoggedIn_redirectsToLoginForm() {
    goToRootUrl();
    assertUrlEquals(routes.HomeController.loginForm(Optional.empty()));
  }

  @Test
  public void homePage_whenLoggedInAsAdmin_redirectsToAdminProgramList() {
    loginAsAdmin();
    goToRootUrl();
    assertUrlEquals(controllers.admin.routes.AdminProgramController.index());
  }

  @Test
  public void homePage_whenLoggedInAsApplicantForFirstTime_redirectsToLanguageForm() {
    loginAsGuest();
    long applicantId = getApplicantId();

    goToRootUrl();

    assertUrlEquals(controllers.applicant.routes.ApplicantInformationController.edit(applicantId));
  }

  @Test
  public void noCredLogin() {
    loginAsGuest();
    // should be redirected to root.
    goTo(routes.HomeController.securePlayIndex());
    assertThat(browser.pageSource()).contains("You are logged in.");

    goTo(routes.ProfileController.myProfile());
    assertThat(browser.pageSource()).contains("GuestClient");
    assertThat(browser.pageSource()).contains(Roles.ROLE_APPLICANT.toString());

    goTo(controllers.admin.routes.AdminProgramController.index());
    assertTrue(browser.pageSource().contains("403"));
  }

  @Test
  public void basicOidcLogin() {
    loginWithSimulatedIdcs();
    goTo(routes.HomeController.securePlayIndex());
    assertThat(browser.pageSource()).contains("You are logged in.");
    goTo(routes.ProfileController.myProfile());
    assertThat(browser.pageSource()).contains("OidcClient");
    assertThat(browser.pageSource()).contains("username@example.com");

    Applicant applicant =
        applicantRepository.lookupApplicant(getApplicantId()).toCompletableFuture().join().get();
    Optional<String> applicantName =
        applicant.getApplicantData().readString(WellKnownPaths.APPLICANT_FIRST_NAME);
    assertThat(applicantName).isPresent();
    assertThat(applicantName.get()).isEqualTo("first");

    applicantName = applicant.getApplicantData().readString(WellKnownPaths.APPLICANT_MIDDLE_NAME);
    assertThat(applicantName).isPresent();
    assertThat(applicantName.get()).isEqualTo("middle");

    applicantName = applicant.getApplicantData().readString(WellKnownPaths.APPLICANT_LAST_NAME);
    assertThat(applicantName).isPresent();
    assertThat(applicantName.get()).isEqualTo("last");
  }

  @Test
  public void mergeLogins() {
    // First, log in as guest and get the applicant ID.
    loginAsGuest();
    goTo(routes.ProfileController.myProfile());
    assertThat(browser.pageSource()).contains("GuestClient");

    long applicantId = getApplicantId();

    // Then, login with IDCS and show that the applicant ID is the same.
    loginWithSimulatedIdcs();
    goTo(routes.ProfileController.myProfile());
    assertThat(browser.pageSource()).contains("OidcClient");
    assertThat(applicantId).isEqualTo(getApplicantId());

    logout();

    loginWithSimulatedIdcs();
    goTo(routes.ProfileController.myProfile());
    assertThat(browser.pageSource()).contains("OidcClient");
    assertThat(applicantId).isEqualTo(getApplicantId());
  }

  @Test
  public void adminTestLogin() {
    loginAsAdmin();

    goTo(routes.HomeController.securePlayIndex());
    assertThat(browser.pageSource()).contains("You are logged in.");

    goTo(routes.ProfileController.myProfile());
    assertThat(browser.pageSource()).contains("FakeAdminClient");
    assertThat(browser.pageSource()).contains(Roles.ROLE_UAT_ADMIN.toString());

    goTo(controllers.admin.routes.AdminProgramController.index());
    assertTrue(browser.pageSource().contains("Programs"));
    assertTrue(browser.pageSource().contains("Create new program"));
  }
}
