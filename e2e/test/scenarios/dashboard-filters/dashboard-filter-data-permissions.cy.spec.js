const { H } = cy;
import { ORDERS_DASHBOARD_ID } from "e2e/support/cypress_sample_instance_data";

function filterDashboard(suggests = true) {
  H.visitDashboard(ORDERS_DASHBOARD_ID);
  cy.contains("Orders");
  cy.contains("Text").click();

  // We should get a suggested response and be able to click it if we're an admin
  if (suggests) {
    cy.findByPlaceholderText("Search the list").type("Main Street");
    cy.contains("100 Main Street").click();
  } else {
    cy.findByPlaceholderText("Search the list").type("100 Main Street").blur();
    cy.wait("@search").should(({ response }) => {
      expect(response.statusCode).to.equal(403);
    });
  }
  cy.contains("Add filter").click({ force: true });
  cy.contains("100 Main Street");
}

describe("support > permissions (metabase#8472)", () => {
  beforeEach(() => {
    cy.intercept(
      "GET",
      `/api/dashboard/${ORDERS_DASHBOARD_ID}/params/*/search/*").as("search`,
    );

    H.restore();
    cy.signInAsAdmin();

    // Setup a dashboard with a text filter
    H.visitDashboard(ORDERS_DASHBOARD_ID);

    H.editDashboard();

    H.setFilter("Text or Category", "Is");

    // Filter the first card by User Address
    H.selectDashboardFilter(
      cy.findByTestId("dashcard-container").first(),
      "Address",
    );

    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.contains("Done").click();
    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.contains("Save").click();
    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.contains("Orders in a dashboard").click();
  });

  it("should allow an admin user to select the filter", () => {
    filterDashboard();
  });

  it("should allow a nodata user to select the filter", () => {
    cy.signIn("nodata");
    filterDashboard();
  });

  it("should not allow a nocollection user to visit the page, hence cannot see the filter", () => {
    cy.signIn("nocollection");
    cy.request({
      method: "GET",
      url: `/api/dashboard/${ORDERS_DASHBOARD_ID}`,
      failOnStatusCode: false,
    }).should((xhr) => {
      expect(xhr.status).to.equal(403);
    });
  });
});
