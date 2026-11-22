' Liga o Ranga Web sem janela. Usado pelo atalho da pasta Inicializar
' (criado pelo LigarComOPC.ps1). Para ver se deu certo: ranga-web.log.
' Sem acentos de proposito: o Windows Script Host le .vbs como ANSI.
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir
' Janela 0 = escondida. False = nao espera terminar.
sh.Run "cmd /c ""build\install\ranga-web\bin\ranga-web.bat"" > ranga-web.log 2>&1", 0, False
