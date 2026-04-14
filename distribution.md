# Retracing publishing

1. Navigate to nodule folder, and generate pom file for that module
```bash
cd <module-folder>
gradle generatePomFileForMavenPublication
```
2. Copy generated pom to module root
```bash
cp build/publications/maven/pom-default.xml pom-dist.xml
```

3. Configure the remote server in *settings.xml*. If you haven't done so previously, then sign in to *https://central.sonatype.com/* 
navigate to *https://central.sonatype.com/usertoken*. Generate a new user token, and then copy the server credentials into *settings.xml*
Ensure that the *settings.xml* is in the parent project's root folder. 

```xml
<settings>
    <servers>
        <!-- add your generated server credentials here -->
        <server>
            <!-- matched by publishing plugin in build section pf pom file -->
            <id>central</id>
            <username>${username}</username>
            <password>${password}</password>
        </server>
    </servers>
</settings>

```

4. Update *GroupId* in the deployment pom file to match namespace in maven central, if it doesn't already
```xml
<groupId>com.akilisha.oss</groupId>
```

5. Ensure that the published artifact is not a SNAPSHOT version.
```xml
<!-- don't use SNAPSHOT -->
<version>0.1.0</version>
```

6. In the *pom-dist.xml* file, configure license, developer details and repository type. Adjust values to match your project details
```xml
<packaging>jar</packaging>
<name>cafeai-core</name>
<description>CafeAi core module</description>
<url>https://github.com/akilisha/cafeai-core</url>

<licenses>
 <license>
   <name>The Apache License, Version 2.0</name>
   <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
 </license>
</licenses>

<developers>
 <developer>
   <id>m41na</id>
   <name>Maina, Stephen</name>
   <email>m41na@yahoo.com</email>
 </developer>
</developers>

<scm>
 <connection>scm:git:git://github.com/akilisha/cafeai-core.git</connection>
 <developerConnection>scm:git:ssh://github.com/akilisha/cafeai-core.git</developerConnection>
 <url>https://github.com/akilisha/cafeai-core</url>
</scm>
```

7. In the *pom-dist.xml* file, remove all *&lt;scope&gt;runtime&lt;/scope&gt;* entries from every dependency and also add testing dependencies
```xml
<properties>
    <junit.version>6.0.3</junit.version>
    <assertj.version>3.27.7</assertj.version>
    <mockito.version>5.23.0</mockito.version>
</properties>

<dependencies>
    <!-- JUnit Jupiter API & Engine -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>${junit.version}</version>
        <scope>test</scope>
    </dependency>

    <!-- JUnit Jupiter Parameterized Tests -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter-params</artifactId>
        <version>${junit.version}</version>
        <scope>test</scope>
    </dependency>

    <!-- AssertJ Fluent Assertions -->
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version>
        <scope>test</scope>
    </dependency>

    <!-- Mockito Core -->
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <version>${mockito.version}</version>
        <scope>test</scope>
    </dependency>

    <!-- Mockito JUnit Jupiter Extension -->
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-junit-jupiter</artifactId>
        <version>${mockito.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

8. In the *pom-dist.xml* file, configure the plugins necessary to satisfy the publishing requirements
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.sonatype.central</groupId>
            <artifactId>central-publishing-maven-plugin</artifactId>
            <version>0.9.0</version>
            <extensions>true</extensions>
            <configuration>
                <!-- should match name of server credentials specified in settings.xml -->
                <publishingServerId>central</publishingServerId>
            </configuration>
        </plugin>

        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version> <configuration>
            <release>23</release>
            <compilerArgs>
                <arg>--enable-preview</arg>
            </compilerArgs>
        </configuration>
        </plugin>

        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-source-plugin</artifactId>
            <version>3.3.1</version>
            <executions>
                <execution>
                    <id>attach-sources</id>
                    <goals>
                        <goal>jar-no-fork</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>

        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-javadoc-plugin</artifactId>
            <version>3.11.2</version> <!-- Use the latest version -->
            <configuration>
                <doclint>none</doclint>
            </configuration>
            <executions>
                <execution>
                    <id>attach-javadocs</id>
                    <goals>
                        <goal>jar</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>

        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-gpg-plugin</artifactId>
            <version>3.2.2</version>
            <executions>
                <execution>
                    <id>sign-artifacts</id>
                    <phase>verify</phase>
                    <goals>
                        <goal>sign</goal>
                    </goals>
                    <configuration>
                        <useAgent>true</useAgent>
                        <defaultKeyring>false</defaultKeyring>
                        <keyname>71337946596FE931DCA600DD8DE042C053D6492A</keyname>
                    </configuration>
                </execution>
            </executions>
        </plugin>

        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>3.5.1</version> <executions>
            <execution>
                <phase>package</phase>
                <goals>
                    <goal>shade</goal>
                </goals>
                <configuration>
                    <transformers>
                        <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                            <!--                <mainClass>com.jaynats.stream.http.JayNatsLiveDemoApp</mainClass>-->
                            <manifestEntries>
                                <Implementation-Title>CafeAi Core Module</Implementation-Title>
                                <Implementation-Version>${project.version}</Implementation-Version>
                                <Implementation-Vendor>CafeAi</Implementation-Vendor>
                            </manifestEntries>
                        </transformer>
                        <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                    </transformers>
                    <finalName>cafeai-core</finalName>
                </configuration>
            </execution>
        </executions>
        </plugin>
    </plugins>
</build>
```

9. Build the project to ensures tests pass and binaries as created
```bash
mvn --settings ../settings.xml -f pom-dist.xml clean package
```

10. Publish binaries to maven central, the manually publish them in *https://central.sonatype.com/publishing* to public repo
```bash
mvn --settings ../settings.xml -f pom-dist.xml -DskipTests deploy
```

11. Miscellaneous - Manually update the pom for deploying. Configuring the 'maven-gpg-plugin' in particular is tricky
```xml
<!-- manual mode -->
<configuration>
    <useAgent>true</useAgent>
    <defaultKeyring>false</defaultKeyring>
    <keyname>your-key-id</keyname>
</configuration>

<!-- auto mode -->
<configuration>
    <useAgent>true</useAgent>
    <defaultKeyring>false</defaultKeyring>
    <keyname>your-key-id</keyname>
    <gpgArguments>
        <arg>--pinentry-mode</arg>
        <arg>loopback</arg>
    </gpgArguments>
</configuration>
```

12. Order of publishing
```bash
cafeai-core
cafeai-memory
cafeai-guardrails
cafeai-observability
cafeai-tools
cafeai-views-mustache
cafeai-streaming
```
