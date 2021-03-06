# JUnit Maven

This plugin integrate Maven Surefire Plugin with Eclipse JUnit. This plugin can
recognize this tags:

* additionalClasspathElements
* argLine
* environmentVariables
* systemPropertiesFile
* systemPropertyVariables

Example:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>RELEASE</version>
  <configuration>
    <additionalClasspathElements>
      <additionalClasspathElement>custompath</additionalClasspathElement>
    </additionalClasspathElements>
    <argLine>-Dfaa=foo</argLine>
    <environmentVariables>
      <FOO>faa</FOO>
    </environmentVariables>
    <systemPropertiesFile>test.properties</systemPropertiesFile>
    <systemPropertyVariables>
      <fii>fuu</fii>
    </systemPropertyVariables>
  </configuration>
</plugin>
```

## Instalation

You use the Update Site: https://dl.bintray.com/danjaredg/junit.maven/
to install this plugin

## License

See the [LICENSE](LICENSE.md) file for license rights and limitations (MIT).