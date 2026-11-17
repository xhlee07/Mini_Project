// Runs the real Android biometric integration test and injects emulator finger touches.
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
const adb = 'C:/Users/User/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const target = ['-s', process.env.ANDROID_SERIAL || 'emulator-5554'];
execFileSync(adb,[...target,'logcat','-c']);
const log = spawn(adb,[...target,'logcat','-s','StaffBiometricTest:I','-v','brief']);
const seen = new Set();
let logText = '', result = '';
const touches = setInterval(()=>{
 if(seen.size>0) {
  try { execFileSync(adb,[...target,'emu','finger','touch','1']); } catch {}
 }
},2000);
log.stdout.on('data',chunk=>{
 logText += chunk;
 for(const marker of ['WAIT_ENABLE_TOUCH','WAIT_POST_SIGNOUT_TOUCH','WAIT_UNLOCK_TOUCH']) {
  if(logText.includes(marker)&&!seen.has(marker)){
   seen.add(marker);console.log(marker);
   setTimeout(()=>{
    try { execFileSync(adb,[...target,'emu','finger','touch','1']); } catch {}
   },1500);
  }
 }
});
const test = spawn(adb,[...target,'shell','am','instrument','-w','-r','-e','class','com.ctfly.app.StaffBiometricStateTest','com.ctfly.staff.test/androidx.test.runner.AndroidJUnitRunner']);
test.stdout.on('data',chunk=>{result+=chunk;process.stdout.write(chunk);});
test.stderr.on('data',chunk=>{result+=chunk;process.stderr.write(chunk);});
const timeout=setTimeout(()=>{test.kill();log.kill();},180000);
const code=await new Promise(resolve=>test.on('exit',resolve));
clearTimeout(timeout);clearInterval(touches);log.kill();
const artifactDirectory = new URL('../build/test-results/staff-fingerprint/', import.meta.url);
await mkdir(artifactDirectory, { recursive: true });
await writeFile(new URL('staff-fingerprint-instrumentation.txt', artifactDirectory), result+'\n'+logText);
if(code!==0||!/OK \(1 test\)/.test(result)||seen.size!==3) process.exitCode=1;
