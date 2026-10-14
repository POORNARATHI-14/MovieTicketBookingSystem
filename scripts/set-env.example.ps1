# 1) Copy this file to  scripts\set-env.ps1   (that copy is git-ignored)
# 2) Put YOUR values below
# 3) In PowerShell run:   . .\scripts\set-env.ps1     (note the leading dot and space)
$env:DB_HOST        = "localhost"
$env:DB_PORT        = "3306"
$env:DB_USERNAME    = "root"
$env:DB_PASSWORD    = "PUT_YOUR_MYSQL_PASSWORD_HERE"

# any random text of 32+ characters (signs login tokens)
$env:JWT_SECRET     = "PUT_A_LONG_RANDOM_TEXT_OF_AT_LEAST_32_CHARACTERS_HERE"

# creates the first ADMIN user at start-up (needed to add movies/theatres/shows)
$env:ADMIN_EMAIL    = "admin@movieticket.local"
$env:ADMIN_PASSWORD = "PUT_AN_ADMIN_PASSWORD_HERE1"

# optional: set to "true" only if RabbitMQ is running
$env:RABBITMQ_ENABLED = "false"

Write-Host "Environment variables set for this PowerShell window." -ForegroundColor Green
