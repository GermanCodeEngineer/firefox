console.log("Running background.js with ")
browser.management.onAdminModeRequest.addListener(async ({ requestId }) => {
  try {
    const { password, authorizedUntil = 0 } = await browser.storage.local.get([
      "password",
      "authorizedUntil",
    ]); // TODO: this storage does not seem safe or overlap-proof at all
    const authorized = Boolean(password) && authorizedUntil > Date.now();

    if (authorized) {
      await browser.storage.local.remove("authorizedUntil");
    }

    browser.management.respondToAdminModeRequest(requestId, authorized);
  } catch (error) {
    console.error("Unable to check Admin Mode authorization", error);
    browser.management.respondToAdminModeRequest(requestId, false);
  }
});
