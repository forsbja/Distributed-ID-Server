all:
	cd ./identity-service && ./gradlew build -x test
clean:
	cd ./identity-service && ./gradlew clean
test:
	cd ./identity-service && ./gradlew test
