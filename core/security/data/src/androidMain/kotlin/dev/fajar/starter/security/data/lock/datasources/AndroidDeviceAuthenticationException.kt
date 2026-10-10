package dev.fajar.starter.security.data.lock.datasources
class AndroidDeviceAuthenticationException(val code: Int) :
    Exception("Device authentication failed.")
