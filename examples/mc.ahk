#Requires AutoHotkey v2.0
; Beispiel: Minecraft ueber die Local-API steuern. Das Skript aendern, nicht die Mod.
; F8 = vorwaerts halten/loslassen, F9 = Hotbar 3 + Rechtsklick tippen, F10 = Zustand zeigen.

global BASE := "http://127.0.0.1:25580"
global PW := "Test123"

Api(method, path, body := "") {
    http := ComObject("WinHttp.WinHttpRequest.5.1")
    http.Open(method, BASE path, false)
    http.SetRequestHeader("X-Password", PW)
    http.SetRequestHeader("Content-Type", "application/json")
    http.SetTimeouts(1000, 1000, 1000, 7000)
    http.Send(body)
    return http.ResponseText
}

holding := false

F8:: {
    global holding
    holding := !holding
    Api("POST", holding ? "/key/down" : "/key/up", '{"key":"forward"}')
}

F9:: {
    Api("POST", "/batch", '{"requests":['
        . '{"path":"/hotbar","body":{"slot":2}},'
        . '{"path":"/key/tap","body":{"key":"use"}}]}')
}

F10:: {
    MsgBox(Api("GET", "/container"))
}
