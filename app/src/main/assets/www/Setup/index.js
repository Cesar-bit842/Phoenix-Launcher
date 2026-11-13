(function () { 
 document.addEventListener('DOMContentLoaded', Main); 
 async function Main() {  
  document.getElementById('ThemeColors').addEventListener('submit', SaveChanges);
  for (var item of document.querySelectorAll('main>form>group:nth-of-type(1)>container>label')){
   item.addEventListener('click', SelectTheme);
  }
  for (var item of document.querySelectorAll('main>form>group:nth-of-type(2)>container>label')){
   item.addEventListener('click', SelectColor);
  }
 }      
 
 async function SelectTheme(event){
  let Objects = document.querySelectorAll('main>form>group:nth-of-type(1)>container input:checked');
  for (let items of Objects) {
   items.checked = false;
  }
  document.getElementById(event.currentTarget.getAttribute("for")).setAttribute("checked", "");
  document.getElementById(event.currentTarget.getAttribute("for")).checked = true;
  
  let color = event.currentTarget.getAttribute("for");
  document.documentElement.setAttribute('ThemeSystem', color); 
 }
 async function SelectColor(event){
  let Objects = document.querySelectorAll('main>form>group:nth-of-type(2)>container input:checked');
  for (let items of Objects) {
   items.checked = false;
  }
  document.getElementById(event.currentTarget.getAttribute("for")).setAttribute("checked", "");
  document.getElementById(event.currentTarget.getAttribute("for")).checked = true;
  
  let color = event.currentTarget.getAttribute("for").replace('"','');
  document.documentElement.style.setProperty('--ThemeColor', color);
 }
 
 async function SaveChanges(event){
  event.preventDefault();
  let Init = { 
    "Theme Color": getComputedStyle(document.documentElement).getPropertyValue('--ThemeColor').trim() || "#ff0000", 
    "Theme System": document.documentElement.getAttribute('ThemeSystem') ?? "TS"
  };

  AndroidBridge.saveConfig(JSON.stringify(Init));
  window.location.href = "../Auth/index.html";
 }
})(); 
