"""Offline fallback: compile current Android Kotlin + Compose and run JVM tests.

Requires an existing Gradle dependency cache, SDK 35 and generated R/BuildConfig
from a previous Android build. This is not Gradle dependency resolution, resource
compilation, APK packaging, lint or a device/UI test. No cache writes or network.
"""
from pathlib import Path
import os, subprocess
base=Path(__file__).resolve().parents[1] / 'frontend/samples/CameraAccessAndroid/app'
cache=Path(os.environ.get('JARVIS_GRADLE_CACHE', str(Path.home() / '.gradle/caches')));mods=cache/'modules-2/files-2.1'
# Cached AAR class jars and library jars; no Gradle services, sockets or downloads.
libs=list((cache/'8.14.1/transforms').glob('*/transformed/*/jars/classes.jar'))
libs+=list((cache/'8.14.1/transforms').glob('*/transformed/*/jars/libs/*.jar'))
for group in ['org.jetbrains.kotlin','org.jetbrains.kotlinx','com.squareup.okhttp3','com.squareup.okio','com.google.code.gson','org.json','org.jetbrains','junit','org.hamcrest','com.meta.wearable','org.webrtc']:
    libs+=list((mods/group).glob('*/*/*/*.jar'))
libs+=[Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platforms/android-35/android.jar',base/'build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar',base/'build/intermediates/javac/debug/compileDebugJavaWithJavac/classes']
# Some local artifacts use other group names.
libs+=list(mods.glob('com.meta*/*/*/*/*.jar'))
libs+=list(mods.glob('androidx.*/*/*/*/*.jar'))
libs+=list(mods.glob('com.google.guava/*/*/*/*.jar'))
cp=os.pathsep.join(map(str,libs))
compiler=list((mods/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20').glob('*/*.jar'))
compiler+=list((mods/'org.jetbrains.intellij.deps/trove4j').glob('*/*/*.jar'))
compiler+=list((mods/'org.jetbrains.kotlin/kotlin-stdlib/2.1.20').glob('*/*.jar'))
compiler+=list((mods/'org.jetbrains.kotlin/kotlin-reflect/1.6.10').glob('*/*.jar'))
compiler+=list((mods/'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20').glob('*/*.jar'))
compiler+=list((mods/'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1').glob('*/*.jar'))
compiler+=list((mods/'org.jetbrains/annotations/13.0').glob('*/*.jar'))
plugin=next((mods/'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.20').glob('*/*.jar'))
import tempfile
working = tempfile.TemporaryDirectory(prefix='jarvis-kotlin-check-')
work = Path(working.name)
out=work / 'main';out.mkdir()
args=['-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',cp,'-Xplugin='+str(plugin),'-d',str(out)]
args+=list(map(str,(base/'src/main/java').rglob('*.kt')))
# Kotlin compiler argfile preserves spaces safely.
argfile=work / 'main.args';argfile.write_text('\n'.join('"'+a.replace('\\','\\\\').replace('"','\\"')+'"' for a in args))
java=str(Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin/java')
r=subprocess.run([java,'-Xmx3g','-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
(work / 'compile.log').write_text(r.stdout)
print(r.stdout[:20000]); print('Compiler exit:',r.returncode)
if r.returncode: raise SystemExit(r.returncode)
testout=work / 'test';testout.mkdir()
testargs=['-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',str(out)+os.pathsep+cp,'-Xfriend-paths='+str(out),'-d',str(testout)]
testargs+=list(map(str,(base/'src/test/java').rglob('*.kt')))
testfile=work / 'tests.args';testfile.write_text('\n'.join('"'+a.replace('\\','\\\\').replace('"','\\"')+'"' for a in testargs))
r=subprocess.run([java,'-Xmx2g','-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(testfile)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
print(r.stdout);print('Test compiler exit:',r.returncode)
if r.returncode: raise SystemExit(r.returncode)
tests=['com.meta.wearable.dat.externalsampleapps.cameraaccess.'+str(p.relative_to(base/'src/test/java/com/meta/wearable/dat/externalsampleapps/cameraaccess')).replace('/','.').removesuffix('.kt') for p in (base/'src/test/java').rglob('*Test.kt')]
r=subprocess.run([java,'-cp',str(testout)+os.pathsep+str(out)+os.pathsep+cp,'org.junit.runner.JUnitCore',*tests],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
(work / 'tests.log').write_text(r.stdout)
print(r.stdout);raise SystemExit(r.returncode)
