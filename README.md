# tut-maven-plugin

This is a Maven plugin that allows you to generate Scala documentation
snippets that contain code that is compiled to ensure the code is valid.
This is just a Maven plugin shell; the original project that does the
heavy lifting can be found [here](http://tpolecat.github.io/tut/).

Releases here will match the version of the corresponding Tut release.
If extra releases of this plugin are required for a given Tut version, a fourth version
component will be added to the plugin's version number.

## Usage

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.spurint.maven.plugins</groupId>
      <artifactId>tut-maven-plugin</artifactId>
      <version>${tut-maven-plugin.version}</version>
      <executions>
        <execution>
          <id>generate-documentation</id>
          <goals>
            <goal>generate-documentation</goal>
          </goals>
        </execution>
        <configuration>
          <sourceDirectory>${project.basedir}/docs-src</sourceDirectory>
          <targetDirectory>${project.basedir}/docs</targetDirectory>
          <nameFilter>.*\\.(md|markdown|txt|htm|html)$</nameFilter>
        </configuration>
      </executions>
    </plugin>
  </plugins>
</build>
```

## Configuration

| Name | Default | Description |
|:-----|:--------|:------------|
| `sourceDirectory` | `${project.basedir}/docs-src` | The source root containing files to parse |
| `targetDirectory` | `${project.basedir}/docs` | The location to place generated documenation |
| `nameFiter` | `.*\\.(md\|markdown\|txt\|htm\|html)$` | A regular expression describing file names to be processed |
| `scalacOptions` | (taken from project) | A list of `<scalacOption>` to pass to the Scala compiler |
| `pluginJars` | (taken from project) | A list of `<pluginJar>`, paths to JAR files of compiler plugins |
