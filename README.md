
# redirector

This is an example HTTP redirector for a client.

The client requests a resource from a URL.

The server re-writes the URL based on cached data in REDIS or, if not in REDIS, from MongoDB

The new URL is returned via HTTP status code 302 (Found) with the Location header filled out to the new URL

Any non-matches are returned with a status code 404 (Not Found)

If needed, the client should be programmed to follow these re-directs ;-)

# Example 
````
$ curl -I http://redirects.herokuapp.com/XYZ/IT/abc.jpg
HTTP/1.1 302 Found
Connection: keep-alive
Date: Mon, 22 Sep 2014 08:57:40 GMT
Location: https://s3-eu-west-1.amazonaws.com/cache-1/IT/abc.jpg
Server: Jetty(7.x.y-SNAPSHOT)
Via: 1.1 vegur
````
# Note
This first implementation drops the first key from the new URL (this is how the first implementation needs it ...)

# TODO
Allow override on whether any keys should be dropped from the input
