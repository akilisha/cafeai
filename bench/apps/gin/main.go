// The same routes on Gin, in release mode with no logger middleware.
package main

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

func main() {
	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.GET("/json", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"message": "Hello, World!"})
	})
	r.GET("/thread", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"virtual": false})
	})
	// Each request runs in its own goroutine, so time.Sleep is the idiomatic wait.
	r.GET("/block", func(c *gin.Context) {
		ms, err := strconv.Atoi(c.DefaultQuery("ms", "100"))
		if err != nil {
			ms = 100
		}
		time.Sleep(time.Duration(ms) * time.Millisecond)
		c.JSON(http.StatusOK, gin.H{"slept": true})
	})
	r.Run(":8080")
}
