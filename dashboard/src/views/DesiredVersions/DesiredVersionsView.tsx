import {Box, Button, Card, CardContent, CardHeader, FormControlLabel, IconButton, Typography} from "@material-ui/core";
import FormGroup from "@material-ui/core/FormGroup";
import {RefreshControl} from "../../common/components/refreshControl/RefreshControl";
import GridTable from "../../common/components/gridTable/GridTable";
import Alert from "@material-ui/lab/Alert";
import React from "react";
import {GridTableCellParams, GridTableColumnParams} from "../../common/components/gridTable/GridTableColumn";
import UpIcon from "@mui/icons-material/ArrowUpward";
import DownIcon from '@mui/icons-material/ArrowDownward';

type Classes = Record<"root" | "content" | "inner" | "versionsTable" |
                      "serviceColumn" | "versionColumn" | "authorColumn" | "timeColumn" | "commentColumn" |
                      "appearedAttribute" | "disappearedAttribute" | "modifiedAttribute" |
                      "controls" | "control" | "alert" | "authorText" | "timeText" |
                      "timeChangeButton", string>

export interface ServiceVersion<Version> {
  service: string
  version: Version
}

export interface VersionInfo {
  author: string
  buildTime: string
  comment: string
}

export class DesiredVersionsView<Version> {
  private title: string
  private desiredVersionsHistory: {time:Date, author: string, versions:ServiceVersion<Version>[]}[]
  private versionsInfo: {version:ServiceVersion<Version>, info: VersionInfo}[]
  private compare: (v1: Version | undefined, v2: Version | undefined) => number
  private serialize: (version: Version) => string
  private parse: (version: string) => Version
  private modify: (desiredVersions: ServiceVersion<Version>[]) => void
  private rerender: () => void
  private refresh: () => void
  private classes: Classes

  private desiredVersions: ServiceVersion<Version>[] = []
  private historyIndex: number = 0
  private time: Date | undefined
  private author: string | undefined

  private error: string | undefined = undefined

  constructor(title: string,
              desiredVersionsHistory: {time:Date, author: string, versions:ServiceVersion<Version>[]}[],
              versionsInfo: {version:ServiceVersion<Version>, info: VersionInfo}[],
              compare: (v1: Version | undefined, v2: Version | undefined) => number,
              serialize: (version: Version) => string,
              parse: (version: string) => Version,
              modify: (desiredVersions: ServiceVersion<Version>[]) => void,
              rerender: () => void, refresh: () => void, classes: Classes) {
    this.title = title
    this.desiredVersionsHistory = desiredVersionsHistory
    this.versionsInfo = versionsInfo
    this.compare = compare
    this.serialize = serialize
    this.parse = parse
    this.modify = modify
    this.rerender = rerender
    this.refresh = refresh
    this.classes = classes
    this.setHistoryIndex(this.desiredVersionsHistory.length - 1)
  }

  getBaseColumns() {
    return [
      {
        name: 'service',
        headerName: 'Service',
        className: this.classes.serviceColumn,
      },
      {
        name: 'version',
        type: 'select',
        headerName: 'Desired Version',
        className: this.classes.versionColumn,
        editable: true
      },
      {
        name: 'author',
        headerName: 'Author',
        className: this.classes.authorColumn,
      },
      {
        name: 'buildTime',
        headerName: 'Build Time',
        type: 'date',
        className: this.classes.timeColumn,
      },
      {
        name: 'comment',
        headerName: 'Comment',
        className: this.classes.commentColumn,
      }
    ] as Array<GridTableColumnParams>
  }

  setVersionsInfo(versionsInfo: {version:ServiceVersion<Version>, info: VersionInfo}[]) {
    this.versionsInfo = versionsInfo
    this.rerender()
  }

  setError(error: string | undefined) {
    this.error = error
    this.rerender()
  }

  makeBaseRows() {
    return this.makeServicesList().map(service => {
      const modifiedVersion = this.desiredVersions.find(v => v.service == service)
      const currentVersion = this.getCurrentDesiredVersions().find(v => v.service == service)
      const version = modifiedVersion ? modifiedVersion : currentVersion!
      const appeared = !currentVersion && !!modifiedVersion
      const disappeared = !!currentVersion && !modifiedVersion
      const modified = !appeared && !disappeared && this.compare(modifiedVersion?.version, currentVersion?.version)
      const className = appeared?this.classes.appearedAttribute:
                        disappeared?this.classes.disappearedAttribute:
                        modified?this.classes.modifiedAttribute:
                        undefined
      const info = this.versionsInfo.find(info => info.version.service == service &&
        this.compare(info.version.version, version?.version) == 0)?.info
      return new Map<string, GridTableCellParams>([
        ['service', {
          value: service,
          className: className
        }],
        ['version', {
          value: this.serialize(version.version),
          className: className,
          select: this.versionsInfo.filter(v => v.version.service == service)
            ?.map(v => ({value: this.serialize(v.version.version),
              description: this.serialize(v.version.version) + ' - ' + v.info.comment } as {value:string, description:string}))
        }],
        ['author', {
          value: info?.author,
          className: className
        }],
        ['buildTime', {
          value: info?.buildTime,
          className: className
        }],
        ['comment', {
          value: info?.comment,
          className: className
        }]
      ])})
  }

  render(columns: GridTableColumnParams[], rows: Map<string, GridTableCellParams>[]) {
    return (
      <Card
        className={this.classes.root}
      >
        <CardHeader
          action={
            <FormGroup row>
              <FormControlLabel
                label={null}
                control={<>
                  <IconButton
                    className={this.classes.timeChangeButton}
                    disabled={this.historyIndex == this.desiredVersionsHistory.length-1}
                    onClick={() => {
                      this.setHistoryIndex(this.historyIndex += 1)
                      this.rerender()
                    }}>
                    <UpIcon/>
                  </IconButton>
                </>
                }
              />
              <FormControlLabel
                label={null}
                control={<>
                  <IconButton
                    className={this.classes.timeChangeButton}
                    disabled={this.historyIndex == 0}
                    onClick={() => {
                      this.setHistoryIndex(this.historyIndex -= 1)
                      this.rerender()
                    }}>
                    <DownIcon/>
                  </IconButton>
                </>
                }
              />
              <FormControlLabel
                label={null}
                control={
                  <Typography className={this.classes.timeText}>Time: {this.time?.toLocaleString()}</Typography>
                }
              />
              <FormControlLabel
                label={null}
                control={
                  <Typography className={this.classes.authorText}>Author: {this.author}</Typography>
                }
              />
              <RefreshControl className={this.classes.control}
                              refresh={() => { this.refresh() }}
              />
            </FormGroup>
          }
          title={this.title}
        />
        <CardContent className={this.classes.content}>
          <div className={this.classes.inner}>
            <GridTable
              className={this.classes.versionsTable}
              columns={columns}
              rows={rows}
              onRowChanged={ (row, values, oldValues) => {
                const service = values.get('service')
                const version = this.parse(values.get('version') as string)
                this.desiredVersions = this.desiredVersions?.map(v => {
                  if (v.service == service) {
                    return { service: v.service, version: version }
                  } else {
                    return v
                  }
                })
                this.rerender()
              }}
            />
            {this.error && <Alert className={this.classes.alert} severity="error">{this.error}</Alert>}
            {this.historyIndex != this.desiredVersionsHistory.length - 1 || this.isModified() ?
              <Box className={this.classes.controls}>
                <Button className={this.classes.control}
                        color="primary"
                        variant="contained"
                        onClick={() => {
                          this.setHistoryIndex(this.desiredVersionsHistory.length - 1)
                          this.rerender()
                        }}
                >
                  Cancel
                </Button>
                <Button className={this.classes.control}
                        color="primary"
                        variant="contained"
                        disabled={!this.isModified()}
                        onClick={() => {
                          this.modify(this.desiredVersions)
                          this.refresh()
                        }}
                >
                  Save
                </Button>
              </Box> : null}
          </div>
        </CardContent>
      </Card>
    )
  }

  private makeServicesList() {
    const services = new Set<string>()
    this.getCurrentDesiredVersions().map(v => v.service).forEach(s => services.add(s))
    this.desiredVersions!.map(v => v.service).forEach(s => services.add(s))
    return Array.from(services)
  }

  private getCurrentDesiredVersions() {
    return this.desiredVersionsHistory![this.desiredVersionsHistory!.length - 1].versions
  }

  private setHistoryIndex(index: number) {
    this.historyIndex = index
    const current = this.desiredVersionsHistory![index]
    this.time = current.time
    this.author = current.author
    this.desiredVersions = [...current.versions]
  }

  private isModified() {
    return this.desiredVersions?.length != this.getCurrentDesiredVersions().length ||
           this.desiredVersions?.find(v1 => {
             const v2 = this.getCurrentDesiredVersions().find(v2 => v2.service == v1.service)
             return this.compare(v1.version, v2?.version) != 0
           })
  }
}
