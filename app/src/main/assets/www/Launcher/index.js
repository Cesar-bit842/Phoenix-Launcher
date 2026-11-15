(function () { 
 document.addEventListener('DOMContentLoaded', Main); 
 async function Main() { 
  for (var obj of document.querySelectorAll("main>Toolbar>button")) {
   obj.addEventListener('click', function(event){   
    let attr = event.currentTarget.getAttribute("pnel");
    for (var item of document.querySelectorAll(`main>Toolbar>button:not([pnel="${attr}"])`)){
     item.removeAttribute("isselected");  
    }
    event.currentTarget.setAttribute("isselected", "");
    TogglePanel(attr);
   });
  }
  //selectAndCopyVersion
  //getVersionDirectoryNames
  //launchGame(
  //String versionName,
  //      String playerName,
  //      String authMode,
  //      String sessionUuid,
  //      String identityToken,
  //      String sessionToken)
 }      
 async function TogglePanel(Panel) { 
  const mainContainerId = 'main>Dashboard'; 
  const mainContainer = document.querySelector(mainContainerId);

  if (!mainContainer) return;

  const computedStyle = window.getComputedStyle(mainContainer);
  const transitionTimeVar = computedStyle.getPropertyValue('--Transition-Time-Of-Objects') || "0s";
  const transitionTimeMs = parseFloat(transitionTimeVar) * 1000;

  const Offset = computedStyle.getPropertyValue('--Translation-Of-Objects') || "translate(10px, 10px)";
  const NrmlOffset = "translate(0px, 0px)";

  const allPanels = mainContainer.querySelectorAll(`${mainContainerId} > *`);

  const targetSelector = `[pnel="${Panel}"]`;
  const panelsToHide = mainContainer.querySelectorAll(`${mainContainerId} > *:not(${targetSelector})`);
  const panelToShow = mainContainer.querySelector(targetSelector);

  for (var i = 0; i < allPanels.length; i++) {
    allPanels[i].style.opacity = "0";
    allPanels[i].style.transform = Offset;
  } 
  if (transitionTimeMs > 0) {
    await Promise_(transitionTimeMs);
  }  
  
  panelToShow.style.display = "flex";  
  for (var j = 0; j < panelsToHide.length; j++) {
    panelsToHide[j].style.display = "none"; 
  } 
  if (transitionTimeMs > 0) {
    await Promise_(transitionTimeMs);
  }
  
  if (panelToShow) { 
    panelToShow.style.opacity = "1";
    panelToShow.style.transform = NrmlOffset;
  }
 }
})(); 
