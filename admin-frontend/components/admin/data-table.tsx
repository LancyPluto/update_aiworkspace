"use client"

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"

export interface Column<T> {
  key: keyof T | string
  title: string
  render?: (value: unknown, item: T) => React.ReactNode
}

interface DataTableProps<T> {
  columns: Column<T>[]
  data: T[]
  className?: string
}

export function DataTable<T extends object>({
  columns,
  data,
  className,
}: DataTableProps<T>) {
  const readValue = (item: T, key: keyof T | string): unknown => {
    return (item as Record<string, unknown>)[String(key)]
  }

  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border border-border bg-card",
        className
      )}
    >
      <Table>
        <TableHeader>
          <TableRow className="border-border hover:bg-transparent">
            {columns.map((column) => (
              <TableHead
                key={String(column.key)}
                className="text-muted-foreground font-medium"
              >
                {column.title}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {data.map((item, index) => (
            <TableRow
              key={index}
              className="border-border transition-colors hover:bg-secondary/50"
            >
              {columns.map((column) => (
                <TableCell key={String(column.key)}>
                  {column.render
                    ? column.render(
                        readValue(item, column.key),
                        item
                      )
                    : String(readValue(item, column.key) ?? "")}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

// 状态徽章组件
interface StatusBadgeProps {
  status: "active" | "inactive" | "pending" | "error" | "timeout"
  label: string
}

export function StatusBadge({ status, label }: StatusBadgeProps) {
  const variants = {
    active: "bg-accent/10 text-accent border-accent/20",
    inactive: "bg-muted text-muted-foreground border-border",
    pending: "bg-chart-5/10 text-chart-5 border-chart-5/20",
    error: "bg-destructive/10 text-destructive border-destructive/20",
    timeout: "bg-amber-500/10 text-amber-600 border-amber-500/20",
  }

  return (
    <Badge
      variant="outline"
      className={cn("font-medium", variants[status])}
    >
      <span
        className={cn(
          "mr-1.5 h-1.5 w-1.5 rounded-full",
          status === "active" && "bg-accent",
          status === "inactive" && "bg-muted-foreground",
          status === "pending" && "bg-chart-5",
          status === "error" && "bg-destructive",
          status === "timeout" && "bg-amber-500"
        )}
      />
      {label}
    </Badge>
  )
}
