# SendX
Send Any File, smart and light

局域网加密文件传输工具，纯Java实现，零外部依赖

## 特性
- UDP 广播自动发现局域网节点
- AES-256-CTR 加密传输 + SHA-256 完整性校验
- 传输前密码握手验证，密码不对立即拒绝
- 实时双向进度条 （速度 / ETA）
- 板顶物理网卡，绕过VPN

## 编译

```bash
./build.sh
```

要求 JDK 17+

## 使用流程

### 1. 双方启动

**机器 A （发送方）：**
```bash
./run.sh
Enter password: 123456

=== SendX -LAN File Transfer (AES-256 Encrypted) ===
  Node: machine-a (id: 4f1c8ea9)
  Bind: 172.20.3.91:56633
  Receive Dir: /path/to/SendX/sendx_received
  
  Ready. Type 'help' for commands.
```

**机器 B （接收方）：**
```bash
./run.sh
Enter password: 123456      # 必须和发送方相同

=== SendX - LAN File Transfer (AES-256 Encrypted) ===
  Node: machine-b (id: 7b3e2a1f)
  Bind: 172.20.3.50:48821
  Receive Dir: /path/to/SendX/sendx_received
  
  Ready. Type 'help' for commands.
```

### 2. 发送文件

在发送方操作
```
> list
  Online nodes:
  [1] machine-b (172.20.3.50:48821)
  
> send 1 ~/documents/big-file.zip
  sending big-file.zip (1.2GB) to machine-b...
  Computing SHA-256...
  Send complete. Time: 27s, Avg speed: 44.4MB/s
```

接收方自动接收，无需操作：
```
> 
  Receiving big-data.zip (1.2GB) from 172.20.3.91...
  Receive complete. Time: 27s, Avg speed: 44.4MB/s
  Save to: ./sendx_received/big-file.zip
```

### 3. 密码不匹配时

发送方立即报错，不浪费传输时间：
```
> send 1 ~/documents/big-file.zip
  Sending big-file.zip (1.2GB) to machine-b...
  Computing SHA-256...
  Authentication failed! Password mismatch
```

接收方提示：
```
  Rejected connection from 172.20.3.91:password mismatch.
```

## 命令

| 命令                     | 说明  |
|------------------------|-----|
| `list`                 | 查看在线节点 |
| `send <#> <file_path>` | 发送文件给节点 # |
| `help`                 | 显示帮助 |
| `exit`                 | 退出 |

## 可选参数

```bash
./run.sh -p 9877        # 指定TCP端口 （默认随机）
./run.sh -n mynode      # 指定节点名 （默认 hostname）
./run.sh -d ./recv      # 指定接收目录 （默认 ./sendx_recieved）
```