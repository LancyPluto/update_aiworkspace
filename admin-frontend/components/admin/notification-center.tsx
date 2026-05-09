"use client"

import { useState } from "react"
import { Bell, Mail, AlertCircle, CheckCircle2, Info, XCircle, MoreHorizontal, Check, Trash2, ExternalLink } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Badge } from "@/components/ui/badge"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { cn } from "@/lib/utils"

type NotificationType = "info" | "success" | "warning" | "error"
type MessageType = "feedback" | "support" | "system"

interface Notification {
  id: string
  type: NotificationType
  title: string
  description: string
  time: string
  read: boolean
}

interface Message {
  id: string
  type: MessageType
  sender: string
  avatar?: string
  subject: string
  preview: string
  time: string
  read: boolean
}

const mockNotifications: Notification[] = [
  {
    id: "1",
    type: "success",
    title: "新工具上架成功",
    description: "AI 图像生成器 v2.0 已成功上架并对所有用户可用",
    time: "5 分钟前",
    read: false,
  },
  {
    id: "2",
    type: "warning",
    title: "API 调用量预警",
    description: "GPT-4 接口本月调用量已达 80%，请注意成本控制",
    time: "1 小时前",
    read: false,
  },
  {
    id: "3",
    type: "error",
    title: "任务执行失败",
    description: "任务 #4521 执行失败，错误码：API_TIMEOUT",
    time: "2 小时前",
    read: false,
  },
  {
    id: "4",
    type: "info",
    title: "系统维护通知",
    description: "计划于今晚 23:00-01:00 进行系统维护升级",
    time: "3 小时前",
    read: true,
  },
  {
    id: "5",
    type: "success",
    title: "新用户注册",
    description: "今日新增 128 名注册用户，会员转化率 12.5%",
    time: "5 小时前",
    read: true,
  },
]

const mockMessages: Message[] = [
  {
    id: "1",
    type: "feedback",
    sender: "张三",
    subject: "关于 AI 写作助手的建议",
    preview: "您好，我在使用 AI 写作助手时发现了一些可以改进的地方...",
    time: "10 分钟前",
    read: false,
  },
  {
    id: "2",
    type: "support",
    sender: "李四",
    subject: "算力充值问题",
    preview: "我昨天充值了 100 算力，但是账户余额没有变化，请帮忙查看...",
    time: "30 分钟前",
    read: false,
  },
  {
    id: "3",
    type: "feedback",
    sender: "王五",
    subject: "新功能请求",
    preview: "希望能增加批量导出功能，这样可以更方便地管理生成的内容...",
    time: "1 小时前",
    read: false,
  },
  {
    id: "4",
    type: "system",
    sender: "系统",
    subject: "每周数据报告",
    preview: "本周平台数据汇总：活跃用户 2,341 人，任务完成 15,678 个...",
    time: "2 小时前",
    read: true,
  },
  {
    id: "5",
    type: "support",
    sender: "赵六",
    subject: "账号登录异常",
    preview: "我的账号在另一个设备上无法登录，提示账号已被锁定...",
    time: "4 小时前",
    read: true,
  },
]

const notificationIcons: Record<NotificationType, React.ReactNode> = {
  info: <Info className="h-4 w-4 text-primary" />,
  success: <CheckCircle2 className="h-4 w-4 text-accent" />,
  warning: <AlertCircle className="h-4 w-4 text-chart-3" />,
  error: <XCircle className="h-4 w-4 text-destructive" />,
}

const messageTypeLabels: Record<MessageType, { label: string; color: string }> = {
  feedback: { label: "反馈", color: "bg-primary/20 text-primary" },
  support: { label: "工单", color: "bg-chart-3/20 text-chart-3" },
  system: { label: "系统", color: "bg-muted text-muted-foreground" },
}

export function NotificationCenter() {
  const [notifications, setNotifications] = useState<Notification[]>(mockNotifications)
  const [messages, setMessages] = useState<Message[]>(mockMessages)
  const [open, setOpen] = useState(false)

  const unreadNotifications = notifications.filter((n) => !n.read).length
  const unreadMessages = messages.filter((m) => !m.read).length
  const totalUnread = unreadNotifications + unreadMessages

  const markNotificationAsRead = (id: string) => {
    setNotifications((prev) =>
      prev.map((n) => (n.id === id ? { ...n, read: true } : n))
    )
  }

  const markMessageAsRead = (id: string) => {
    setMessages((prev) =>
      prev.map((m) => (m.id === id ? { ...m, read: true } : m))
    )
  }

  const markAllNotificationsAsRead = () => {
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })))
  }

  const markAllMessagesAsRead = () => {
    setMessages((prev) => prev.map((m) => ({ ...m, read: true })))
  }

  const deleteNotification = (id: string) => {
    setNotifications((prev) => prev.filter((n) => n.id !== id))
  }

  const deleteMessage = (id: string) => {
    setMessages((prev) => prev.filter((m) => m.id !== id))
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="relative">
          <Bell className="h-5 w-5" />
          {totalUnread > 0 && (
            <span className="absolute -right-0.5 -top-0.5 flex h-5 w-5 items-center justify-center rounded-full bg-primary text-[10px] font-medium text-primary-foreground">
              {totalUnread > 99 ? "99+" : totalUnread}
            </span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent 
        align="end" 
        className="w-[400px] p-0 border-border bg-card shadow-2xl"
        sideOffset={8}
      >
        <Tabs defaultValue="notifications" className="w-full">
          <div className="flex items-center justify-between border-b border-border px-4 py-3">
            <h3 className="font-semibold text-foreground">消息中心</h3>
            <TabsList className="h-8 bg-secondary/50">
              <TabsTrigger value="notifications" className="h-7 px-3 text-xs">
                通知
                {unreadNotifications > 0 && (
                  <Badge variant="secondary" className="ml-1.5 h-4 px-1 text-[10px] bg-primary/20 text-primary">
                    {unreadNotifications}
                  </Badge>
                )}
              </TabsTrigger>
              <TabsTrigger value="messages" className="h-7 px-3 text-xs">
                邮件
                {unreadMessages > 0 && (
                  <Badge variant="secondary" className="ml-1.5 h-4 px-1 text-[10px] bg-primary/20 text-primary">
                    {unreadMessages}
                  </Badge>
                )}
              </TabsTrigger>
            </TabsList>
          </div>

          <TabsContent value="notifications" className="m-0">
            <div className="flex items-center justify-between border-b border-border/50 px-4 py-2">
              <span className="text-xs text-muted-foreground">
                {unreadNotifications > 0 ? `${unreadNotifications} 条未读` : "暂无未读通知"}
              </span>
              {unreadNotifications > 0 && (
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 text-xs text-primary hover:text-primary"
                  onClick={markAllNotificationsAsRead}
                >
                  <Check className="mr-1 h-3 w-3" />
                  全部已读
                </Button>
              )}
            </div>
            <ScrollArea className="h-[320px]">
              {notifications.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
                  <Bell className="mb-2 h-8 w-8 opacity-50" />
                  <p className="text-sm">暂无通知</p>
                </div>
              ) : (
                <div className="divide-y divide-border/50">
                  {notifications.map((notification) => (
                    <div
                      key={notification.id}
                      className={cn(
                        "group relative flex gap-3 px-4 py-3 transition-colors hover:bg-secondary/50",
                        !notification.read && "bg-primary/5"
                      )}
                    >
                      <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-secondary">
                        {notificationIcons[notification.type]}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-start justify-between gap-2">
                          <p className={cn(
                            "text-sm leading-tight",
                            !notification.read ? "font-medium text-foreground" : "text-foreground/80"
                          )}>
                            {notification.title}
                          </p>
                          <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                className="h-6 w-6 opacity-0 group-hover:opacity-100 transition-opacity"
                              >
                                <MoreHorizontal className="h-3.5 w-3.5" />
                              </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end" className="w-32">
                              {!notification.read && (
                                <DropdownMenuItem onClick={() => markNotificationAsRead(notification.id)}>
                                  <Check className="mr-2 h-3.5 w-3.5" />
                                  标为已读
                                </DropdownMenuItem>
                              )}
                              <DropdownMenuItem 
                                onClick={() => deleteNotification(notification.id)}
                                className="text-destructive focus:text-destructive"
                              >
                                <Trash2 className="mr-2 h-3.5 w-3.5" />
                                删除
                              </DropdownMenuItem>
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </div>
                        <p className="mt-0.5 text-xs text-muted-foreground line-clamp-2">
                          {notification.description}
                        </p>
                        <p className="mt-1 text-[10px] text-muted-foreground/70">
                          {notification.time}
                        </p>
                      </div>
                      {!notification.read && (
                        <div className="absolute left-1.5 top-1/2 -translate-y-1/2 h-1.5 w-1.5 rounded-full bg-primary" />
                      )}
                    </div>
                  ))}
                </div>
              )}
            </ScrollArea>
          </TabsContent>

          <TabsContent value="messages" className="m-0">
            <div className="flex items-center justify-between border-b border-border/50 px-4 py-2">
              <span className="text-xs text-muted-foreground">
                {unreadMessages > 0 ? `${unreadMessages} 封未读` : "暂无未读邮件"}
              </span>
              {unreadMessages > 0 && (
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 text-xs text-primary hover:text-primary"
                  onClick={markAllMessagesAsRead}
                >
                  <Check className="mr-1 h-3 w-3" />
                  全部已读
                </Button>
              )}
            </div>
            <ScrollArea className="h-[320px]">
              {messages.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
                  <Mail className="mb-2 h-8 w-8 opacity-50" />
                  <p className="text-sm">暂无邮件</p>
                </div>
              ) : (
                <div className="divide-y divide-border/50">
                  {messages.map((message) => (
                    <div
                      key={message.id}
                      className={cn(
                        "group relative flex gap-3 px-4 py-3 transition-colors hover:bg-secondary/50 cursor-pointer",
                        !message.read && "bg-primary/5"
                      )}
                      onClick={() => markMessageAsRead(message.id)}
                    >
                      <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-primary/20 to-accent/20 text-sm font-medium text-foreground">
                        {message.sender.charAt(0)}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <span className={cn(
                            "text-sm",
                            !message.read ? "font-medium text-foreground" : "text-foreground/80"
                          )}>
                            {message.sender}
                          </span>
                          <Badge variant="secondary" className={cn("h-4 px-1.5 text-[10px]", messageTypeLabels[message.type].color)}>
                            {messageTypeLabels[message.type].label}
                          </Badge>
                        </div>
                        <p className={cn(
                          "mt-0.5 text-sm truncate",
                          !message.read ? "text-foreground" : "text-foreground/70"
                        )}>
                          {message.subject}
                        </p>
                        <p className="mt-0.5 text-xs text-muted-foreground line-clamp-1">
                          {message.preview}
                        </p>
                        <p className="mt-1 text-[10px] text-muted-foreground/70">
                          {message.time}
                        </p>
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button
                              variant="ghost"
                              size="icon"
                              className="h-6 w-6 opacity-0 group-hover:opacity-100 transition-opacity"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <MoreHorizontal className="h-3.5 w-3.5" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-32">
                            <DropdownMenuItem>
                              <ExternalLink className="mr-2 h-3.5 w-3.5" />
                              查看详情
                            </DropdownMenuItem>
                            <DropdownMenuItem 
                              onClick={() => deleteMessage(message.id)}
                              className="text-destructive focus:text-destructive"
                            >
                              <Trash2 className="mr-2 h-3.5 w-3.5" />
                              删除
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                      {!message.read && (
                        <div className="absolute left-1.5 top-1/2 -translate-y-1/2 h-1.5 w-1.5 rounded-full bg-primary" />
                      )}
                    </div>
                  ))}
                </div>
              )}
            </ScrollArea>
          </TabsContent>

          <div className="border-t border-border p-2">
            <Button variant="ghost" className="w-full justify-center text-xs text-muted-foreground hover:text-foreground">
              查看全部消息
            </Button>
          </div>
        </Tabs>
      </PopoverContent>
    </Popover>
  )
}
