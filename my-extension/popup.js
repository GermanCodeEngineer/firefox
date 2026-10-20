const passwordInput = document.getElementById("passwordInput");
const status = document.getElementById("status");

document.getElementById("unlockBtn").addEventListener("click", async () => {
  const inputPassword = passwordInput.value;
  if (!inputPassword) {
    status.textContent = "Enter a password.";
    passwordInput.focus();
    return;
  }

  try {
    const { password } = await browser.storage.local.get("password");
    if (password && inputPassword !== password) {
      status.textContent = "Incorrect password.";
      passwordInput.select();
      return;
    }

    const values = {
      authorizedUntil: Date.now() + 60_000,
    };
    if (!password) {
      values.password = inputPassword;
    }
    await browser.storage.local.set(values);
    window.close();
  } catch (error) {
    status.textContent = "Could not save authorization. Try again.";
    console.error("Unable to save Admin Mode authorization", error);
  }
});
