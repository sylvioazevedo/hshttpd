## Welcome to the High Speed HTTP Daemon (hshttpd) project.

Yes, it is another web server like Apache and Ngnix. I decided to write a new almost 20 years late...

### Why a new web server?
First, I was restricted by a download policy at work and it was easier to write a new one than to overwrite the policies to download a regular web server. Second, I could not find a web server that stored all files in memory, yes they have some cache mechanism, but as memory nowadays is cheap, I was expected to found a web server entire based on memory. So this is the purpose of the hshttpd, load once every single file in a base directory (www) put them into a hash map (memory), using as key the HTTP url (GET /some-dir/some-page). Last, it was awesome, I am a coder by nature, so it was a good exercise.


I have based this code on the netty.io http file server implementation (https://netty.io/4.1/xref/io/netty/example/http/file/package-summary.html).

It is a IntelliJ project, using Gradle. The server was developed in Groovy, please feel free to convert into the programming language you like most.

From git clone command:

> git clone https://github.com/sylvioazevedo/hshttpd.git

Just gradle it:

> gradle shadowJar

And then just invoke like a Jar program:

> java -jar build/libs/hshttpd-1.0-SNAPSHOT-all.jar 

This project is licensed as GPL v3.0
