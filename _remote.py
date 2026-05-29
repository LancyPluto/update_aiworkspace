import paramiko  
c=paramiko.SSHClient()  
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())  
c.connect('8.134.93.203',22,'root','KeChuangDianAi17728033019',timeout=15)  
