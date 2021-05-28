dependencies {
    api(project(":graphql-dgs"))

    implementation("net.bytebuddy:byte-buddy")
    implementation("io.micrometer:micrometer-core")
    implementation("commons-codec:commons-codec")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("com.netflix.spectator:spectator-api:0.130.+")

    compileOnly(project(":graphql-dgs-spring-boot-starter"))
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-web")

    testImplementation(project(":graphql-dgs-spring-boot-starter"))
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
}
