(function () {
  document.addEventListener('DOMContentLoaded', Main);

  async function Main() {
    setTimeout(async function () {  
     if(await ValidateConfig()){ 
      if(await ValidateAccount()){
       window.location.href = "./Launcher/index.html";
       return;
      }
      window.location.href = "./Auth/index.html";
      return;
     }
     window.location.href = "./Setup/index.html"; 
     return;
    }, 3000);
  }
  
  async function ValidateConfig(){
   try {
     return AndroidBridge.ConfigurationFileExists();
   } catch (e) {
     return false;
   }
  }
  async function ValidateAccount(){
   try {
     return AndroidBridge.AuthorizedHytaleAccount();
   }catch (e) {
     return false;
   }
  }
})();
