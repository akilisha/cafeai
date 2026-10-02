package io.cafeai.dev;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads one version of the app: its freshly compiled classes and its resource
 * directories first, everything else (CafeAI, libraries) from the parent as usual.
 *
 * <p>App classes and resources are child-first on purpose: in a Gradle or IDE run the
 * parent's classpath also holds the app's previously compiled classes, and a normal
 * parent-first loader would keep handing those out -- a reload that changes nothing.
 */
final class AppClassLoader extends URLClassLoader {

    private final Path classes;
    private final List<Path> resourceDirs;

    AppClassLoader(int generation, Path classes, List<Path> resourceDirs, List<Path> extraJars, ClassLoader parent) {
        super("cafeai-dev-" + generation, urls(classes, resourceDirs, extraJars), parent);
        this.classes = classes;
        this.resourceDirs = resourceDirs;
    }

    private static URL[] urls(Path classes, List<Path> resourceDirs, List<Path> extraJars) {
        try {
            URL[] urls = new URL[1 + resourceDirs.size() + extraJars.size()];
            int i = 0;
            urls[i++] = classes.toUri().toURL();
            for (Path r : resourceDirs) urls[i++] = r.toUri().toURL();
            for (Path j : extraJars) urls[i++] = j.toUri().toURL();
            return urls;
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = Files.exists(classes.resolve(name.replace('.', '/') + ".class"))
                        ? findClass(name)                       // this version's code
                        : super.loadClass(name, false);         // CafeAI, libraries, the JDK
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        for (Path dir : resourceDirs) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                try {
                    return file.toUri().toURL();
                } catch (java.net.MalformedURLException e) {
                    break;
                }
            }
        }
        return super.getResource(name);
    }
}
