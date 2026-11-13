(function () {
  document.addEventListener('DOMContentLoaded', Main);

  async function Main() {
    setTimeout(function () {
      let configValidation = false;
      try {
        configValidation = AndroidBridge.ConfigurationFileExists();
      } catch (e) {
        console.error("Error validando archivo de configuración:", e);
        configValidation = false;
      }

      if (!configValidation) {
        window.location.href = "./Setup/index.html";
        return;
      }

      let hytaleValidation = false;
      try {
        hytaleValidation = Boolean(AndroidBridge.AuthorizedHytaleAccount());
      } catch (e) {
        console.error("Error validando cuenta de Hytale:", e);
        hytaleValidation = false;
      }

      if (hytaleValidation) {
        window.location.href = "./Launcher/index.html";
      } else {
        window.location.href = "./Auth/index.html";
      }
    }, 3000);
  }
})();