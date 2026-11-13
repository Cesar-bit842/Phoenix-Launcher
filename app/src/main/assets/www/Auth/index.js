(function () { 
 document.addEventListener('DOMContentLoaded', Main); 
 async function Main() {
    document.getElementById("HytaleAuth").addEventListener("click", Auth);
 }      
 async function Auth(){
    try{
     AndroidBridge.loginHytale()
    }catch(e){}
 }
})(); 
